package net.tfminecraft.recycler.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
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
  @Test void publicationForcesEveryDirectoryAncestorAndRawParserRefusesAmbiguousDocuments()throws Exception{
    Path file=root.resolve("new/owner/item.json");var observed=new ArrayList<Path>();
    try(var disk=mockStatic(QueueFiles.class,CALLS_REAL_METHODS)){
      disk.when(()->QueueFiles.force(any())).thenAnswer(call->{observed.add(call.getArgument(0));return call.callRealMethod();});QueueFiles.write(file,"bytes".getBytes(),false);
      var expected=new ArrayList<Path>();for(Path p=file.toAbsolutePath().getParent();p!=null;p=p.getParent())expected.add(p);assertEquals(expected,observed);
    }
    for(String source:List.of("null","[]","broken","{\"a\":1,\"a\":2}","{\"a\":[]} {}"))assertThrows(IOException.class,()->QueueJson.parse(source));
    assertTrue(QueueJson.parse("{\"a\":[{\"kept\":null}]} ").getAsJsonArray("a").get(0).getAsJsonObject().get("kept").isJsonNull());
  }
  @Test void paperInventoryCheckpointRequestsSaveDataBeforeItsCallerCanAcknowledgeTheEffect(){var player=mock(org.bukkit.entity.Player.class);InventoryCheckpoint.save(player);verify(player).saveData();}
  @Test void missingMandatoryDigestAlgorithmFailsClosed() throws Exception {
    try(var algorithms=mockStatic(java.security.MessageDigest.class)){
      algorithms.when(()->java.security.MessageDigest.getInstance("SHA-256")).thenThrow(new java.security.NoSuchAlgorithmException("missing"));
      assertThrows(AssertionError.class,()->ReturnStore.hash(new byte[0]));
    }
  }
}
