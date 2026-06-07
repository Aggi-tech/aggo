package com.aggitech.aggo

import com.aggitech.aggo.dialect.MySqlDialect
import com.aggitech.aggo.dialect.MySqlTriggerDialect
import com.aggitech.aggo.dialect.OracleTriggerDialect
import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.dialect.PostgresTriggerDialect
import com.aggitech.aggo.migration.migrationPlan
import com.aggitech.aggo.migration.migrationSchema
import com.aggitech.aggo.notify.ChannelNameQualifier
import com.aggitech.aggo.notify.LongNotifyCodec
import com.aggitech.aggo.notify.NotifyChannel
import com.aggitech.aggo.notify.ReconnectPolicy
import com.aggitech.aggo.notify.StringNotifyCodec
import com.aggitech.aggo.schema.LongCodec
import com.aggitech.aggo.schema.NotifyTrigger
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import com.aggitech.aggo.schema.TriggerEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.r2dbc.spi.Row
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private object NotificationUsers : Table<Unit>("notification_users") {
    val id = column("id", LongCodec, isPrimaryKey = true) { null }
    val email = column("email", StringCodec) { null }

    override fun fromRow(row: Row): Unit = Unit
}

private val NotificationChannel = NotifyChannel("user_events", StringNotifyCodec)

private fun notificationTrigger(
    events: Set<TriggerEvent> = setOf(TriggerEvent.Insert),
    payloadSql: String = "NEW.id",
) = NotifyTrigger(
    name = "trg_notify_user_events",
    table = NotificationUsers,
    channel = NotificationChannel,
    events = events,
    payloadSql = payloadSql,
)

class ReactiveNotificationsBehaviorTest : BehaviorSpec({

    given("a typed notification channel") {
        `when`("its name contains SQL metacharacters, whitespace, or quotes") {
            then("construction fails before the name can reach generated SQL") {
                listOf("events;drop", "user events", "user'events").forEach { invalidName ->
                    shouldThrow<IllegalArgumentException> {
                        NotifyChannel(invalidName, StringNotifyCodec)
                    }
                }
            }
        }

        `when`("a schema prefix qualifier is used") {
            then("logical names map deterministically to tenant-specific physical names") {
                ChannelNameQualifier.None.qualify("user_events") shouldBe "user_events"
                ChannelNameQualifier.SchemaPrefix("tenant_a").qualify("user_events") shouldBe
                    "tenant_a__user_events"
            }
        }
    }

    given("notification payload codecs") {
        `when`("a numeric payload is absent, blank, invalid, or outside Long range") {
            then("decoding returns null without throwing") {
                listOf(null, "", " ", "not-a-number", "9223372036854775808").forEach { raw ->
                    LongNotifyCodec.decode(raw) shouldBe null
                }
            }
        }

        `when`("an 8 kB text payload is encoded and decoded") {
            then("the codec preserves every byte without truncation") {
                val payload = "x".repeat(8 * 1024)
                StringNotifyCodec.decode(StringNotifyCodec.encode(payload)) shouldBe payload
            }
        }
    }

    given("an exponential reconnect policy") {
        `when`("successive reconnect attempts are requested") {
            then("delays grow exponentially, cap at maxDelay, and stop after maxAttempts") {
                val policy = ReconnectPolicy.ExponentialBackoff(
                    initialDelay = 100.milliseconds,
                    maxDelay = 1.seconds,
                    multiplier = 2.0,
                    maxAttempts = 5,
                )

                (1..6).map(policy::nextDelay) shouldBe listOf(
                    100.milliseconds,
                    200.milliseconds,
                    400.milliseconds,
                    800.milliseconds,
                    1.seconds,
                    null,
                )
            }
        }
    }

    given("a new notification trigger in a migration schema") {
        `when`("a PostgreSQL migration plan is generated") {
            then("table DDL precedes function and trigger DDL without an outbox table") {
                val schema = migrationSchema(
                    version = "v1",
                    tables = listOf(NotificationUsers),
                    dialect = PostgresDialect,
                    triggers = listOf(notificationTrigger()),
                )
                val plan = migrationPlan(schema, PostgresDialect, triggerDialect = PostgresTriggerDialect)

                plan.steps.first().change shouldBe "create table notification_users"
                plan.steps.filter { it.audit?.targetType == "trigger" } shouldHaveSize 3
                plan.steps.none { it.audit?.targetType == "outbox table" } shouldBe true
            }
        }

        `when`("a MySQL migration plan is generated") {
            then("the managed outbox is created before trigger DDL") {
                val schema = migrationSchema(
                    version = "v1",
                    tables = listOf(NotificationUsers),
                    dialect = MySqlDialect,
                    triggers = listOf(notificationTrigger()),
                )
                val plan = migrationPlan(schema, MySqlDialect, triggerDialect = MySqlTriggerDialect)
                val outboxIndex = plan.steps.indexOfFirst { it.audit?.targetType == "outbox table" }
                val triggerIndex = plan.steps.indexOfFirst { it.audit?.targetType == "trigger" }

                (outboxIndex >= 0) shouldBe true
                (triggerIndex > outboxIndex) shouldBe true
            }
        }

        `when`("the TriggerDialect is omitted") {
            then("planning fails instead of silently dropping notification DDL") {
                val schema = migrationSchema(
                    version = "v1",
                    tables = listOf(NotificationUsers),
                    dialect = PostgresDialect,
                    triggers = listOf(notificationTrigger()),
                )

                shouldThrow<IllegalArgumentException> {
                    migrationPlan(schema, PostgresDialect)
                }
            }
        }
    }

    given("a multi-event trigger carrying the changed row id") {
        val trigger = notificationTrigger(
            events = linkedSetOf(TriggerEvent.Insert, TriggerEvent.Update, TriggerEvent.Delete),
        )

        `when`("MySQL DDL is rendered") {
            then("each event receives an independent trigger with the correct row image") {
                val creates = MySqlTriggerDialect.renderNotifyTriggerDdl(trigger)
                    .filter { it.contains("CREATE TRIGGER") }

                creates shouldHaveSize 3
                creates.single { it.contains(" INSERT ON ") } shouldContain "NEW.id"
                creates.single { it.contains(" UPDATE ON ") } shouldContain "NEW.id"
                creates.single { it.contains(" DELETE ON ") } shouldContain "OLD.id"
            }
        }

        `when`("PostgreSQL DDL is rendered") {
            then("the trigger function selects OLD for DELETE and NEW for INSERT or UPDATE") {
                val function = PostgresTriggerDialect.renderNotifyTriggerDdl(trigger).first()

                function shouldContain "TG_OP"
                function shouldContain "OLD.id"
                function shouldContain "NEW.id"
            }
        }

        `when`("Oracle DDL is rendered") {
            then("the trigger selects OLD only for DELETE and NEW for INSERT or UPDATE") {
                val ddl = OracleTriggerDialect.renderNotifyTriggerDdl(trigger).single()

                ddl shouldContain "DELETING"
                ddl shouldContain ":OLD.id"
                ddl shouldContain ":NEW.id"
                ddl shouldNotContain "\n/"
            }
        }
    }
})
