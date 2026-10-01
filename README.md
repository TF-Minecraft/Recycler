# Recycler

> Give old equipment another life as useful materials.

Recycler adds a salvage station to TF-Minecraft. Players deposit a supported item, inspect the materials it will return, and confirm whether to dismantle it. The result depends on what the item was made from and how much durability it has left.

It connects several crafting systems to a shared recycling experience, helping surplus equipment feed back into future projects. Recovery is partial, so recycling remains a trade-off between keeping an item and reclaiming some of its value.

## Features

- **Preview before committing** — see the expected material return in the station's interface before confirming the recycle.
- **Condition-based recovery** — worn equipment yields less than an equivalent item at full durability.
- **Crafting-system support** — recover materials from supported AdvancedCrafting items, Magic gear, GunsAndGadgets items, and goldsmithing jewelry. Each returns the materials that actually went into the item, not the recipe as it reads today; items crafted before their plugin recorded this cannot be recycled.
- **Alloy scrap salvage** — recover recorded base materials and catalysts, including gems, from a failed AdvancedCrafting alloy forge. Each consumed material unit rolls independently.
- **Per-type return rates** — each kind of item (AdvancedCrafting gear, alloy scrap, Magic gear, guns, jewelry, and recipe items) returns its own configurable share of the materials it was made from.
- **Additional salvage recipes** — handle other supported items through dedicated recycling recipes, including runes that return enchanted dust.
- **Protection for socketed items** — refuse supported mage weapons containing runes, and goldsmithing jewelry containing socketed gems, so dismantling does not silently consume them.
- **Visible completion** — finish recycling with station sounds, particles, and recovered items appearing in the world.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/Recycler/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Tests and coverage

Run `mvn -B --no-transfer-progress clean verify` with Java 21 and the pinned plugin dependencies installed by `.github/scripts/prepare-release.sh` and the shared CI setup action. The suite uses JUnit 5, Mockito and MockBukkit to exercise configuration, integration providers, inventory events, escrow persistence/recovery, commands, plugin lifecycle and scheduled effects.

JaCoCo requires **100% line, branch and instruction coverage** across all production classes, with no exclusions. Reports are written to `target/site/jacoco/index.html` and `target/site/jacoco/jacoco.xml`; build and release CI publish the reports. Mocked external plugin APIs do not replace testing on a real Minecraft server.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.

Scrap recovery requires AdvancedCrafting 2.2.5 or newer. `return_rates.alloy_scrap` sets the chance per base material unit. `scrap_catalyst_return_rates.tiers` sets catalyst chances by the live AdvancedCrafting ingredient tier (defaults: 1% / 25% / 50% / 75% for tiers 1-4); `default` covers unlisted tiers. `whitelist_paths` allows case-insensitive exact item paths or prefixes ending in `*`, defaults to `[m.gemstones.*]`, and excludes every other catalyst; an empty list excludes all catalysts. The recorded base bypasses this whitelist and always uses `return_rates.alloy_scrap`. Legacy `scrap_gem_return_rates` rates remain a fallback when the new section is absent. Rates use 0.0-1.0 and invalid values are clamped or reset to defaults. Each recorded unit in each stacked scrap rolls once on confirmation, and failed rolls still consume the scrap. The preview lists possible quantities and chances without rolling. Older base-tagged scrap returns only its recorded base, since its catalysts were never saved.
