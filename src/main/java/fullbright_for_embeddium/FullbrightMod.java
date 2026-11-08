package fullbright_for_embeddium;

import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(FullbrightMod.MOD_ID)
public class FullbrightMod {
    public static final String MOD_ID = "fullbright_for_embeddium";
    private static final Logger LOGGER = LogManager.getLogger();

    private static boolean enabled = false;

    public FullbrightMod() {
        LOGGER.info("Fullbright for Embeddium initialized!");
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }
}
