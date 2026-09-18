# Recycler - Architecture

Scaffold aligned with Research, BirdMessenger, and AdvancedCrafting patterns. Gameplay ships in batches (see `IMPLEMENTATION_BATCHES.md`).

## Package map

```text
net.tfminecraft.recycler/
  Recycler.java              # bootstrap, folders, reload
  Cache.java                 # scalars from config.yml
  GuiCache.java              # item refs from gui.yml
  Messages.java              # messages.yml

  loader/
    ConfigLoader.java
    GuiLoader.java
    RecipeLoader.java        # recipes/*.yml fallback registry

  model/
    RecycleOutput.java
    RecycleSession.java

  event/
    RecycleCompleteEvent.java

  provider/
    RecycleProvider.java
    RecycleContext.java
    RecycleResult.java
    RecycleProviderChain.java
    AdvancedCraftingProvider.java
    GunsAndGadgetsProvider.java
    ConfigProvider.java

  manager/
    RecyclerManager.java     # Listener: station, GUI lifecycle
    InventoryManager.java    # GUI build + preview
    EscrowManager.java       # crash-safe held items

  gui/
    RecyclerGuiHolder.java

  util/
    GridLayout.java
    DurabilityScaler.java
    RecycleGuard.java
    ItemRef.java
    ItemGive.java

  command/
    CommandManager.java
```

## Provider chain

Specialized providers run before the config fallback:

| Priority | Provider | When active |
|----------|----------|-------------|
| 10 | `AdvancedCraftingProvider` | AdvancedCrafting plugin present |
| 20 | `GunsAndGadgetsProvider` | GunsAndGadgets present + GG provenance done |
| max | `ConfigProvider` | Always (yaml recipes) |

Resolution flow:

1. `canHandle(item)` on each provider in order.
2. `resolveBaseOutputs(item)` returns base material amounts.
3. `RecycleContext` applies `max_return_rate` (default 0.8) and durability factor.
4. `floor(base * rate * durability * stackSize)` per output line.

## Escrow (crash safety)

The GUI is **not** the source of truth for the input item.

1. On accept: remove from player inventory, store in `EscrowManager`, write `data/escrow/<uuid>.json` immediately.
2. On cancel/close/quit: return from escrow, delete file.
3. On confirm: clear escrow first, then consume and spawn outputs.
4. On disable: return all online players; offline entries move to `data/pending_returns/` (batch 2).
5. On join: deliver any pending return file (batch 2).

BirdMessenger and Research only keep items in memory or GUI slots - Recycler must be stricter.

## Dependencies

- **Required:** TLibs, ItemsAdder (station block + icons)
- **Soft:** AdvancedCrafting, GunsAndGadgets, MMOItems

AC/GG providers require stamped provenance from those plugins. Admin escrow tooling: `/recycler escrow list|return`.

## What not to add yet

- One file per GUI slot
- Hardcoded item ids outside yaml
- Putting the escrow item only in the chest inventory without disk backup
