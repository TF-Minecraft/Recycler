# Recycler - Implementation batches

Work in order. Each batch should compile and be testable before the next.

## Prerequisite (GunsAndGadgets)

**Batch GG-1:** Implement part provenance + revision system in GunsAndGadgets.

See `gunsandgadgets/docs/REVISION_SYSTEM.md`. Recycler `GunsAndGadgetsProvider` is implemented (Batch 5).

---

## Batch 1 - Scaffold (this commit)

- [x] Maven project, plugin.yml, config/gui/messages/recipes yaml
- [x] Bootstrap class, loaders, Cache, provider interface + stubs
- [x] GridLayout, EscrowManager skeleton, GUI shell open on station click
- [x] Documentation (`ARCHITECTURE.md`, `SYSTEM.md`, this file)

**Test:** `/recycler reload`, right-click station opens empty GUI, close does not crash.

---

## Batch 2 - Escrow + inventory deposit

- [x] `InventoryClickEvent`: click player inv item -> escrow + preview refresh
- [x] Reject non-recyclable items with sound/message
- [x] `InventoryCloseEvent` / quit / disable return paths complete
- [x] Persist offline: `pending_returns` on disable, deliver on `PlayerJoinEvent`
- [x] Scan orphaned `data/escrow/*.json` on enable

**Test:** Deposit item, restart server, item returns on join or disable.

---

## Batch 3 - AdvancedCrafting provider

- [x] Add AdvancedCrafting as compile dependency or reflection bridge
- [x] Implement `AdvancedCraftingProvider` via `CraftProvenance`
- [x] Map CraftInput kinds to tlibs output paths
- [x] Confirm + zero-yield guard (implemented in Batch 4)

**Test:** Recycle a crafted AC item into scaled ingredients/alloys.

---

## Batch 4 - Confirm, close, spawn outputs

- [x] Confirm slot click handler
- [x] Close inventory on confirm before spawning items
- [x] `ResultSpawnEffects`-style kick-out per output (copy from Research or shared util)
- [x] Config-driven sounds/particles for open, accept, confirm, complete
- [x] Optional confirm wave animation on slot 10

**Test:** Confirm closes GUI; items pop out of station and can be picked up.

---

## Batch 5 - GunsAndGadgets provider

**Requires GG-1c (provenance + refresh).**

- [x] Read `gg_craft_parts` from gun PDC via `GunCraftProvenance`
- [x] Sum part costs from live `GunPart` definitions (`PartLoader`)
- [x] Skip broken guns and guns with missing stamped part ids

**Test:** Craft gun, recycle into part input materials at scaled rate.

---

## Batch 6 - Polish

- [x] `RecycleCompleteEvent` for economy/professions hooks
- [x] Blacklist / permission tuning (`deposit` config + `RecycleGuard`)
- [x] MMOItems durability integration if needed beyond vanilla Damageable
- [x] Admin command to inspect escrow state (`/recycler escrow list|return`)

---

## Batch status

| Batch | Status |
|-------|--------|
| GG-1 (GunsAndGadgets) | Done (GG-1c provenance + refresh) |
| 1 Scaffold | Done |
| 2 Escrow | Done |
| 3 AC provider | Done |
| 4 Confirm/VFX | Done |
| 5 GG provider | Done |
| 6 Polish | Done |
