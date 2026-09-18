# Recycler - System design

Player-facing recycling station at ItemsAdder furniture `iaf(tfmc:recycling_station)`.

## Station interaction

1. Right-click furniture (TLibs `BlockChecker` on configured path).
2. Open 27-slot GUI (9x3 chest).
3. Click item in player inventory to deposit into the station (one stack max for stackables).
4. Right side previews scaled outputs; left side shows input at slot 10.
5. Click confirm (slot 0, `ia.mcicons:icon_confirm`) to recycle.
6. Close without confirm returns the escrowed item.

## GUI layout (fixed slots)

| Slot | Role |
|------|------|
| 0 | Confirm (`ia.mcicons:icon_confirm`) |
| 3, 12, 21 | Arrow decorators (`ia.mcicons:icon_right_gray`, blank display, hidden tooltip) |
| 10 | Input item display |
| Cols 0-2 except 10 | Gray glass filler (blank display, hidden tooltip) |
| Cols 4-8 | Preview outputs (up to 15 slots) |

Reference: `util/GridLayout.java`.

## Return math

Provider recipes (AdvancedCrafting, GunsAndGadgets):

```
final_amount = floor(base_amount * max_return_rate * durability_factor * stack_amount)
```

Config recipes (`recipes/*.yml`):

```
final_amount = floor(base_amount * durability_factor * stack_amount)
```

- `max_return_rate`: config default `0.8` (80% cap). Applies only to AC/GG providers, not yaml config recipes.
- `durability_factor`: `0.0` when broken, `1.0` when full. Vanilla `Damageable` items and MMOItems custom durability NBT when MMOItems is present.
- `stack_amount`: full stack placed in station (one stack per deposit).
- Use `floor`; zero yield blocks confirm when `block_confirm_when_zero_yield` is true.

## Provider inputs

### AdvancedCrafting (crafted items)

Read `CraftProvenance` from item PDC (`ac_craft_inputs` JSON list of kind/id/amount/revision).

Map `ingredient.*` inputs to live `Ingredient.getPath()` x stamped amount. Map `alloy.*` inputs by decomposing each alloy's forge recipe (base + catalyst ingredient paths) x stamped amount. Use **live** yaml definitions when resolving (revision sync like AC stat refresh).

Raw AC ingredients/alloys (`ac_ingredient_id` / `ac_alloy_id`) are not provenance-backed - handle via config recipes or a future AC rule.

### GunsAndGadgets (guns)

Read stamped part list from gun PDC (`gg_craft_parts`), sum each `GunPart.getCost()` from live `parts.yml` via `GunsAndGadgetsProvider`. Broken guns and guns with missing stamped ids are not handled by this provider.

### Config fallback (`recipes/*.yml`)

Recipe id is a label; matching uses the `input` tlibs path.

```yaml
recipes:
  iron_sword:
    input: v.iron_sword
    outputs:
      - v.iron_ingot 2
      - m.materials.steel_ingot 1
```

Each output line is `path amount` or `path(amount)`. Legacy map-style outputs under a path key are still supported.

Matched via `TLibs ItemChecker`. Durability scaling applies automatically.

## Confirm flow

1. Validate escrow + non-zero yield.
2. Set `session.confirmed = true` (so close handler does not return input).
3. Clear escrow file.
4. `player.closeInventory()` immediately so outputs can be picked up in the world.
5. Play processing VFX at station block.
6. Spawn each output with Research-style kick-out (`ResultSpawnEffects` pattern): upward velocity, crit trail, staggered ticks.

## Sounds and effects (whole interaction)

Configured in `config.yml` under `effects`:

| Moment | Config key |
|--------|------------|
| Open station | `open` |
| Item accepted | `input_accept` |
| Item rejected | `input_reject` |
| Preview refresh | `preview_refresh` |
| Confirm click | `confirm` |
| Complete | `complete` |
| Cancel / return | `cancel` |

Batch 4 adds particles and confirm wave (Research `playConfirmRefreshWave` style).

## Deposit policy

Configured under `deposit` in `config.yml`. `RecycleGuard` runs before provider resolution:

- `whitelist_mode`: only paths in `whitelist_paths` may be deposited (prefix match with trailing `*` supported).
- Otherwise `blacklist_paths` block matching paths.
- `block_unbreakable`: reject items with `ItemMeta.isUnbreakable()`.

Rejected deposits use `station.blocked` and the input-reject sound.

## Profession hooks

`RecycleCompleteEvent` fires after a successful confirm (player, input clone, provider id, scaled outputs, station location). Other plugins (e.g. Professions) may listen for perks or logging.

`professions.recycler_1/2/3` currently unlock crafting recipes (raw gold/iron/diamonds). Perks that bump `max_return_rate` or gate station access are not implemented in Recycler itself.
