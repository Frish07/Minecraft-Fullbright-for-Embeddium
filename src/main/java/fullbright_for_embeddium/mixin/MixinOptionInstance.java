package fullbright_for_embeddium.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import fullbright_for_embeddium.FullbrightMod;

@Mixin(OptionInstance.class)
public class MixinOptionInstance<T> {
    @SuppressWarnings("unchecked")
    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void onGet(CallbackInfoReturnable<T> cir) {
        if (FullbrightMod.isEnabled()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null) {
                if ((Object) this == mc.options.gamma()) {
                    cir.setReturnValue((T) Double.valueOf(100.0D));
                }
            }
        }
    }
}
