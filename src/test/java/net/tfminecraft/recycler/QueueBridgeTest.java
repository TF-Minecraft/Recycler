package net.tfminecraft.recycler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.*;
import net.tfminecraft.recycler.ownership.*;
import net.tfminecraft.recycler.manager.*;
import net.tfminecraft.recycler.provider.*;
import net.tfminecraft.tlibs.character.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class QueueBridgeTest extends TestSupport {
  @Test void providerOwnerCurrentnessAndLivingInfoAreRequiredAndReviewedHeirsCannotCrossAccounts() throws Exception {
    var bridge=new CharacterQueueBridge();var player=server.addPlayer();var first=token(player);var ref=new CharacterRef(first.owner().account(),first.owner().character());
    assertTrue(bridge.current((Player)null).isEmpty());assertFalse(bridge.current(null,player));assertTrue(bridge.heir(null).isEmpty());
    var proper=sessions.get(player.getUniqueId());var foreign=new CharacterSession(new CharacterRef(UUID.randomUUID(),UUID.randomUUID()),20);
    when(characters.currentSession(player)).thenReturn(Optional.of(foreign));when(characters.isCurrent(foreign)).thenReturn(true);assertTrue(bridge.current(player).isEmpty());
    when(characters.currentSession(player)).thenReturn(Optional.of(proper));when(characters.isCurrent(proper)).thenReturn(false);assertTrue(bridge.current(player).isEmpty());
    when(characters.isCurrent(proper)).thenReturn(true);infos.remove(ref.characterId());assertTrue(bridge.current(player).isEmpty());
    infos.put(ref.characterId(),new CharacterInfo(new CharacterRef(UUID.randomUUID(),ref.characterId()),"Wrong",CharacterStatus.ALIVE,null,Set.of(),0,null));assertTrue(bridge.current(player).isEmpty());
    infos.put(ref.characterId(),new CharacterInfo(ref,"Dead",CharacterStatus.DEAD,null,Set.of(),0,null));assertTrue(bridge.current(player).isEmpty());
    infos.put(ref.characterId(),new CharacterInfo(ref,"Alive",CharacterStatus.ALIVE,null,Set.of(),0,null));assertEquals(first,bridge.current(player).orElseThrow());
    assertTrue(bridge.current(first,player));assertFalse(bridge.current(new QueueIdentity.Token(first.owner(),22),player));
    when(characters.legacyHeir(player.getUniqueId())).thenReturn(Optional.of(foreign.ref()));assertTrue(bridge.heir(player.getUniqueId()).isEmpty());
    when(characters.legacyHeir(player.getUniqueId())).thenReturn(Optional.of(ref));assertEquals(first.owner(),bridge.heir(player.getUniqueId()).orElseThrow());assertTrue(bridge.heirsReady());
  }
  @Test void endEventFinishesItsCapturedNoncurrentSessionAndStartRequiresTheExactNewSession() throws Exception {
    var e=new EscrowManager();var inventory=mock(InventoryManager.class);var manager=new RecyclerManager(e,inventory,mock(RecycleProviderChain.class));
    when(plugin.getRecyclerManager()).thenReturn(manager);when(plugin.getEscrowManager()).thenReturn(e);var player=server.addPlayer();var first=token(player);manager.getOrCreateSession(player);
    var holder=new net.tfminecraft.recycler.gui.RecyclerGuiHolder(first);var menu=server.createInventory(holder,27);holder.setInventory(menu);player.openInventory(menu);
    e.putEscrow(player.getUniqueId(),new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND));
    var outgoing=sessions.get(player.getUniqueId());sessions.put(player.getUniqueId(),null);CharacterQueueBridge.start(plugin);
    server.getPluginManager().callEvent(new CharacterSessionEndEvent(player,outgoing,CharacterSessionEndEvent.Reason.SWITCH));
    assertEquals("pending",e.store().rows(first.owner()).getFirst().get("state").getAsString());assertTrue(player.getInventory().isEmpty());assertTrue(manager.quiescent(first.owner()));
    server.getPluginManager().callEvent(new CharacterSessionStartEvent(player,outgoing));assertTrue(player.getInventory().isEmpty());
    var incoming=new CharacterSession(outgoing.ref(),2);sessions.put(player.getUniqueId(),incoming);
    server.getPluginManager().callEvent(new CharacterSessionStartEvent(player,incoming));assertEquals(org.bukkit.Material.DIAMOND,player.getInventory().getItem(0).getType());
  }
  @Test void actualOlderTlibs23ReleaseLoadsTheFailClosedQueueAndEstateAdapters() throws Exception {
    Path repository=Path.of(System.getProperty("maven.repo.local",Path.of(System.getProperty("user.home"),".m2/repository").toString()));Path older=repository.resolve("me/plugins/tlibs/2.3.0/tlibs-2.3.0.jar");assertTrue(Files.isRegularFile(older));
    var classes=QueueIdentity.class.getProtectionDomain().getCodeSource().getLocation();
    try(var loader=new URLClassLoader(new java.net.URL[]{classes,older.toUri().toURL()},getClass().getClassLoader()){
      @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
        if(name.startsWith("net.tfminecraft.tlibs")||name.startsWith("net.tfminecraft.recycler.ownership")){
          Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;
        }return super.loadClass(name,resolve);
      }
    }){
      Class<?> api=loader.loadClass(Characters.class.getName());assertTrue(api.getProtectionDomain().getCodeSource().getLocation().toString().contains("2.3.0.jar"));assertThrows(NoSuchMethodException.class,()->api.getMethod("legacyHeir",UUID.class));
      Class<?> adapter=loader.loadClass(QueueIdentity.class.getName());Object identity=adapter.getMethod("load").invoke(null);
      assertEquals(Optional.empty(),adapter.getMethod("current",Player.class).invoke(identity,server.addPlayer()));
      Class<?> token=loader.loadClass(QueueIdentity.Token.class.getName());assertEquals(false,adapter.getMethod("current",token,Player.class).invoke(identity,null,null));
      assertEquals(false,adapter.getMethod("heirsReady").invoke(identity));assertEquals(Optional.empty(),adapter.getMethod("heir",UUID.class).invoke(identity,UUID.randomUUID()));
      loader.loadClass(QueueEstate.class.getName()).getMethod("start",Recycler.class).invoke(null,plugin);
    }
  }
}
