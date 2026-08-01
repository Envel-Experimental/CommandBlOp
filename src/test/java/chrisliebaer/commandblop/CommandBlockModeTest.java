package chrisliebaer.commandblop;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandBlockModeTest {
	
	// имитирует enum-поле режима командного блока в NMS-пакете:
	// в 1.16 это TileEntityCommand$Type, в 1.20+ CommandBlockEntity$Mode
	private enum FakeMode { REDSTONE, SEQUENCE, AUTO }
	
	private static class FakePacket {
		public String command = "say hi";
		public boolean trackOutput = false;
		public FakeMode mode = FakeMode.SEQUENCE;
	}
	
	@Test
	void resolvesModeFromEnumFieldRegardlessOfClassName() {
		assertEquals("SEQUENCE", CommandBlockMode.fromPacketHandle(new FakePacket()));
	}
	
	@Test
	void throwsWhenNoCommandBlockModeFieldPresent() {
		assertThrows(RuntimeException.class, () -> CommandBlockMode.fromPacketHandle(new Object()));
	}
	
	@Test
	void mapsModesToBlockTypes() {
		assertEquals(Material.COMMAND_BLOCK, CommandBlockMode.toMaterial("REDSTONE"));
		assertEquals(Material.CHAIN_COMMAND_BLOCK, CommandBlockMode.toMaterial("SEQUENCE"));
		assertEquals(Material.REPEATING_COMMAND_BLOCK, CommandBlockMode.toMaterial("AUTO"));
	}
	
	@Test
	void rejectsUnknownModes() {
		assertThrows(RuntimeException.class, () -> CommandBlockMode.toMaterial("BANANA"));
	}
}
