package fullbright_for_embeddium;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

public class KeybindHandler {
    public static KeyMapping toggleKey;

    @Mod.EventBusSubscriber(modid = FullbrightMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ModBusRegister {
        @SubscribeEvent
        public static void registerKeybindings(RegisterKeyMappingsEvent event) {
            toggleKey = new KeyMapping(
                    "key.fullbright_for_embeddium.toggle",
                    KeyConflictContext.IN_GAME,
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_G,
                    "key.categories.misc"
            );
            event.register(toggleKey);
        }
    }

    @Mod.EventBusSubscriber(modid = FullbrightMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static class ForgeBusRegister {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END && toggleKey != null) {
                while (toggleKey.consumeClick()) {
                    boolean newState = !FullbrightMod.isEnabled();
                    FullbrightMod.setEnabled(newState);

                    Minecraft mc = Minecraft.getInstance();
                    if (mc.levelRenderer != null) {
                        mc.levelRenderer.allChanged();
                    }

                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                Component.literal("§6[Fullbright] §e" + (newState ? "Enabled" : "Disabled")),
                                true
                        );
                    }
                }
            }
        }
    }
}
