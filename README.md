# Recycler

> Give old equipment another life as useful materials.

Recycler adds a salvage station to TF-Minecraft. Players deposit a supported item, inspect the materials it will return, and confirm whether to dismantle it. The result depends on what the item was made from and how much durability it has left.

It connects several crafting systems to a shared recycling experience, helping surplus equipment feed back into future projects. Recovery is partial, so recycling remains a trade-off between keeping an item and reclaiming some of its value.

## Features

- **Preview before committing** — see the expected material return in the station's interface before confirming the recycle.
- **Condition-based recovery** — worn equipment yields less than an equivalent item at full durability.
- **Crafting-system support** — recover materials from supported AdvancedCrafting items, Magic gear, GunsAndGadgets items, and goldsmithing jewelry using their recorded crafting inputs.
- **Alloy scrap salvage** — recover recorded base materials and eligible catalysts, including gems, from a failed AdvancedCrafting alloy forge.
- **Artifact salvage** — break down Magic artifacts for enchanted dust according to rarity. Stored aura is lost.
- **Per-type return rates** — tune material returns separately for crafted equipment, scrap, artifacts, and recipe-based salvage.
- **Additional salvage recipes** — handle other supported items through dedicated recycling recipes, including runes that return enchanted dust.
- **Protection for socketed items** — refuse supported mage weapons containing runes, and goldsmithing jewelry containing socketed gems, so dismantling does not silently consume them.
- **Visible completion** — finish recycling with station sounds, particles, and recovered items appearing in the world.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/Recycler/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

Return rates, artifact yields, crafting records, and alloy scrap recovery are described in the [system design guide](https://github.com/TF-Minecraft/Docs/blob/main/projects/Recycler/docs/SYSTEM.md).

## Tests and coverage

Run `mvn -B --no-transfer-progress clean verify` with Java 21 after preparing the pinned private and shared dependencies using the [build dependency guide](https://github.com/TF-Minecraft/Docs/blob/main/PIPELINES.md#build-dependencies). The suite uses JUnit 5, Mockito and MockBukkit to exercise configuration, integration providers, inventory events, escrow persistence/recovery, commands, plugin lifecycle and scheduled effects.

JaCoCo requires **100% line, branch and instruction coverage** across all production classes, with no exclusions. Coverage reports are written to `target/site/jacoco/index.html` and `target/site/jacoco/jacoco.xml`; build and release CI publish the reports. Surefire test results are in `target/surefire-reports/`. Mocked external plugin APIs do not replace testing on a real Minecraft server.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
