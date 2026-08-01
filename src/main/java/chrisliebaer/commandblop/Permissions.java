package chrisliebaer.commandblop;

import lombok.experimental.UtilityClass;
import org.bukkit.entity.Player;

@UtilityClass
public class Permissions {
	
	public static final String ALL = "commandblop.*";
	public static final String FAKE_OP = "commandblop.fakeop";
	public static final String BREAK = "commandblop.break";
	public static final String VIEW = "commandblop.view";
	public static final String EDIT = "commandblop.edit";
	public static final String PLACE = "commandblop.place";
	
	public static boolean has(Player player, String permission) {
		return player.hasPermission(ALL) || player.hasPermission(permission);
	}
}
