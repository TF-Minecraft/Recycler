package net.tfminecraft.recycler.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueueFilesTest {
  @TempDir Path root;
  @Test void immutableCreatesRefuseRacesAndUnsupportedLinksWhileReplacementsRequireAtomicMoves()throws Exception{
    Path file=root.resolve("new/sub/item");byte[] rival="rival".getBytes();
    try(var disk=mockStatic(Files.class,CALLS_REAL_METHODS)){
      disk.when(()->Files.createLink(eq(file),any())).thenAnswer(call->{Files.write(file,rival);return call.callRealMethod();});
      assertThrows(FileAlreadyExistsException.class,()->QueueFiles.write(file,"planned".getBytes(),false));
    }assertArrayEquals(rival,Files.readAllBytes(file));
    try(var disk=mockStatic(Files.class,CALLS_REAL_METHODS)){
      disk.when(()->Files.createLink(any(),any())).thenThrow(new UnsupportedOperationException("links unsupported"));assertThrows(IOException.class,()->QueueFiles.write(root.resolve("unsupported"),rival,false));
      disk.when(()->Files.move(any(Path.class),any(Path.class),eq(StandardCopyOption.ATOMIC_MOVE),eq(StandardCopyOption.REPLACE_EXISTING))).thenThrow(new AtomicMoveNotSupportedException("a","b","unsupported"));assertThrows(IOException.class,()->QueueFiles.write(file,"new".getBytes(),true));
    }assertArrayEquals(rival,Files.readAllBytes(file));QueueFiles.delete(file);QueueFiles.delete(file);
  }
  @Test void publicationForcesItsFolderAndTheParentOfEachCreatedFolderAndRawParserRefusesAmbiguousDocuments()throws Exception{
    Path base=root.toAbsolutePath(),file=base.resolve("new/owner/item.json");var observed=new ArrayList<Path>();
    try(var disk=mockStatic(QueueFiles.class,CALLS_REAL_METHODS)){
      disk.when(()->QueueFiles.force(any())).thenAnswer(call->{observed.add(call.getArgument(0));return null;});QueueFiles.write(file,"bytes".getBytes(),false);
      assertEquals(List.of(base.resolve("new/owner"),base.resolve("new"),base),observed);assertArrayEquals("bytes".getBytes(),Files.readAllBytes(file));
      observed.clear();QueueFiles.write(file,"next".getBytes(),true);assertEquals(List.of(base.resolve("new/owner"),base.resolve("new")),observed);assertArrayEquals("next".getBytes(),Files.readAllBytes(file));
      try(var left=Files.list(file.getParent())){assertEquals(List.of(file),left.toList());}
    }
    Path drive=base.getRoot().resolve("recycler-missing-"+UUID.randomUUID());
    assertEquals(List.of(drive.resolve("a"),drive,base.getRoot()),QueueFiles.syncDirectories(drive.resolve("a")));
    assertEquals(List.of(base,base.getParent()),QueueFiles.syncDirectories(base));assertEquals(List.of(base.getRoot()),QueueFiles.syncDirectories(base.getRoot()));
    for(String source:List.of("null","[]","broken","{\"a\":1,\"a\":2}","{\"a\":[]} {}"))assertThrows(IOException.class,()->QueueJson.parse(source));
    assertTrue(QueueJson.parse("{\"a\":[{\"kept\":null}]} ").getAsJsonArray("a").get(0).getAsJsonObject().get("kept").isJsonNull());
  }
  @Test void onlyPathsThisProcessFullyPublishedSkipAByteIdenticalReplacement()throws Exception{
    Path file=root.resolve("ownership/estates/journal.json");var writes=new ArrayList<Path>();var failSync=new boolean[1];
    JsonObject one=QueueJson.parse("{\"a\":1}"),two=QueueJson.parse("{\"a\":2}");Files.createDirectories(file.getParent());Files.write(file,QueueJson.bytes(one));
    try(var disk=mockStatic(QueueFiles.class,CALLS_REAL_METHODS)){
      disk.when(()->QueueFiles.force(any())).thenAnswer(call->{if(failSync[0])throw new IOException("sync failed");return null;});
      disk.when(()->QueueFiles.write(any(),any(),anyBoolean())).thenAnswer(call->{writes.add(call.getArgument(0));return call.callRealMethod();});
      ReturnStore.write(file,one);ReturnStore.write(file,one);assertEquals(1,writes.size());
      failSync[0]=true;assertThrows(IOException.class,()->ReturnStore.write(file,two));failSync[0]=false;assertEquals(2,ReturnStore.read(file).get("a").getAsInt());
      ReturnStore.write(file,two);ReturnStore.write(file,two);assertEquals(3,writes.size());
      Files.delete(file);ReturnStore.write(file,two);assertEquals(4,writes.size());
      QueueFiles.delete(file);Files.write(file,QueueJson.bytes(two));ReturnStore.write(file,two);assertEquals(5,writes.size());
    }
  }
  @Test void paperInventoryCheckpointRequestsSaveDataBeforeItsCallerCanAcknowledgeTheEffect(){var player=mock(org.bukkit.entity.Player.class);InventoryCheckpoint.save(player);verify(player).saveData();}
  @Test void missingMandatoryDigestAlgorithmFailsClosed() throws Exception {
    try(var algorithms=mockStatic(java.security.MessageDigest.class)){
      algorithms.when(()->java.security.MessageDigest.getInstance("SHA-256")).thenThrow(new java.security.NoSuchAlgorithmException("missing"));
      assertThrows(AssertionError.class,()->ReturnStore.hash(new byte[0]));
    }
  }
}
