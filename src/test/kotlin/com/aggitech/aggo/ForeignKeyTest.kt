package com.aggitech.aggo

import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.migration.addForeignKeyConstraintsSql
import com.aggitech.aggo.schema.ForeignKey
import com.aggitech.aggo.schema.ForeignKeyAction
import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.r2dbc.spi.Row

// ── Fixtures ──────────────────────────────────────────────────────────────────

private data class Buyer(val id: Int, val buyerName: String)

private object BuyersTable : Table<Buyer>("buyers") {
    val id        = column("id", IntCodec, isPrimaryKey = true) { it.id }
    val buyerName = column("name", StringCodec) { it.buyerName }
    override fun fromRow(row: Row): Buyer = Buyer(id.required(row), buyerName.required(row))
}

private data class Sku(val id: Int, val code: String)

private object SkusTable : Table<Sku>("skus") {
    val id   = column("id", IntCodec, isPrimaryKey = true) { it.id }
    val code = column("code", StringCodec) { it.code }
    override fun fromRow(row: Row): Sku = Sku(id.required(row), code.required(row))
}

private data class PurchaseOrder(val id: Int, val buyerId: Int, val skuId: Int)

private object PurchaseOrdersTable : Table<PurchaseOrder>("purchase_orders") {
    val id      = column("id", IntCodec, isPrimaryKey = true) { it.id }
    val buyerId = column("buyer_id", IntCodec) { it.buyerId }
        .references(BuyersTable.id, onDelete = ForeignKeyAction.CASCADE)
    val skuId   = column("sku_id", IntCodec) { it.skuId }
        .references(SkusTable.id, onDelete = ForeignKeyAction.RESTRICT, onUpdate = ForeignKeyAction.CASCADE)
    override fun fromRow(row: Row): PurchaseOrder =
        PurchaseOrder(id.required(row), buyerId.required(row), skuId.required(row))
}

private data class Label(val id: Int, val buyerId: Int, val text: String)

private object LabelsTable : Table<Label>("labels") {
    val id      = column("id", IntCodec, isPrimaryKey = true) { it.id }
    val buyerId = column("buyer_id", IntCodec) { it.buyerId }
        .references(
            BuyersTable.id,
            onDelete = ForeignKeyAction.CASCADE,
            constraintName = "fk_labels_buyer",
        )
    val text = column("text", StringCodec) { it.text }
    override fun fromRow(row: Row): Label = Label(id.required(row), buyerId.required(row), text.required(row))
}

// ── Tests ─────────────────────────────────────────────────────────────────────

class ForeignKeyTest : BehaviorSpec({

    given("columns declared with foreign-key references") {
        `when`("the table metadata is materialized") {
            then("each relationship preserves its columns and referential actions") {
                PurchaseOrdersTable.foreignKeys shouldHaveSize 2

                val buyerFk: ForeignKey =
                    PurchaseOrdersTable.foreignKeys.single { it.column.name == "buyer_id" }
                buyerFk.column shouldBe PurchaseOrdersTable.buyerId
                buyerFk.referencedColumn shouldBe BuyersTable.id
                buyerFk.onDelete shouldBe ForeignKeyAction.CASCADE
                buyerFk.onUpdate shouldBe ForeignKeyAction.RESTRICT

                val skuFk = PurchaseOrdersTable.foreignKeys.single { it.column.name == "sku_id" }
                skuFk.onDelete shouldBe ForeignKeyAction.RESTRICT
                skuFk.onUpdate shouldBe ForeignKeyAction.CASCADE
            }
        }

        `when`("constraint names are resolved") {
            then("generated names are deterministic and explicit names take precedence") {
                PurchaseOrdersTable.foreignKeys.single { it.column.name == "buyer_id" }.effectiveName shouldBe
                    "fk_purchase_orders_buyer_id"
                LabelsTable.foreignKeys.single().effectiveName shouldBe "fk_labels_buyer"
            }
        }

        `when`("PostgreSQL migration DDL is generated") {
            then("each relationship becomes a quoted ALTER TABLE statement with its actions") {
                PurchaseOrdersTable.addForeignKeyConstraintsSql(PostgresDialect) shouldBe listOf(
                    """ALTER TABLE "purchase_orders" ADD CONSTRAINT "fk_purchase_orders_buyer_id" """ +
                        """FOREIGN KEY ("buyer_id") REFERENCES "buyers" ("id") ON DELETE CASCADE ON UPDATE RESTRICT;""",
                    """ALTER TABLE "purchase_orders" ADD CONSTRAINT "fk_purchase_orders_sku_id" """ +
                        """FOREIGN KEY ("sku_id") REFERENCES "skus" ("id") ON DELETE RESTRICT ON UPDATE CASCADE;""",
                )
            }
        }
    }

    given("a table without foreign-key references") {
        `when`("migration DDL is generated") {
            then("no foreign-key statement is emitted") {
                BuyersTable.foreignKeys shouldBe emptyList()
                BuyersTable.addForeignKeyConstraintsSql(PostgresDialect) shouldBe emptyList()
            }
        }
    }
})
