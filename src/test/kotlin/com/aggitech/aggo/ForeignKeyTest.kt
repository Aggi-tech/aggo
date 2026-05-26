package com.aggitech.aggo

import com.aggitech.aggo.dialect.PostgresDialect
import com.aggitech.aggo.migration.addForeignKeyConstraintsSql
import com.aggitech.aggo.schema.ForeignKey
import com.aggitech.aggo.schema.ForeignKeyAction
import com.aggitech.aggo.schema.IntCodec
import com.aggitech.aggo.schema.StringCodec
import com.aggitech.aggo.schema.Table
import io.kotest.core.spec.style.StringSpec
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

class ForeignKeyTest : StringSpec({

    "references() registers the FK on the table" {
        PurchaseOrdersTable.foreignKeys shouldHaveSize 2
    }

    "FK column and referencedColumn are recorded correctly" {
        val fk: ForeignKey = PurchaseOrdersTable.foreignKeys.first { it.column.name == "buyer_id" }
        fk.column shouldBe PurchaseOrdersTable.buyerId
        fk.referencedColumn shouldBe BuyersTable.id
    }

    "FK onDelete and onUpdate actions are recorded" {
        val buyerFk = PurchaseOrdersTable.foreignKeys.first { it.column.name == "buyer_id" }
        buyerFk.onDelete shouldBe ForeignKeyAction.CASCADE
        buyerFk.onUpdate shouldBe ForeignKeyAction.RESTRICT

        val skuFk = PurchaseOrdersTable.foreignKeys.first { it.column.name == "sku_id" }
        skuFk.onDelete shouldBe ForeignKeyAction.RESTRICT
        skuFk.onUpdate shouldBe ForeignKeyAction.CASCADE
    }

    "effectiveName defaults to fk_<table>_<column>" {
        val fk = PurchaseOrdersTable.foreignKeys.first { it.column.name == "buyer_id" }
        fk.effectiveName shouldBe "fk_purchase_orders_buyer_id"
    }

    "constraintName overrides the generated name" {
        val fk = LabelsTable.foreignKeys.first()
        fk.effectiveName shouldBe "fk_labels_buyer"
    }

    "addForeignKeyConstraintsSql (migration layer) generates ALTER TABLE with dialect quoting" {
        val stmts = PurchaseOrdersTable.addForeignKeyConstraintsSql(PostgresDialect)
        stmts shouldHaveSize 2
        stmts[0] shouldBe
            """ALTER TABLE "purchase_orders" ADD CONSTRAINT "fk_purchase_orders_buyer_id" """ +
            """FOREIGN KEY ("buyer_id") REFERENCES "buyers" ("id") ON DELETE CASCADE ON UPDATE RESTRICT;"""
        stmts[1] shouldBe
            """ALTER TABLE "purchase_orders" ADD CONSTRAINT "fk_purchase_orders_sku_id" """ +
            """FOREIGN KEY ("sku_id") REFERENCES "skus" ("id") ON DELETE RESTRICT ON UPDATE CASCADE;"""
    }

    "table with no FK references produces no DDL" {
        BuyersTable.foreignKeys shouldHaveSize 0
        BuyersTable.addForeignKeyConstraintsSql(PostgresDialect) shouldHaveSize 0
    }
})
