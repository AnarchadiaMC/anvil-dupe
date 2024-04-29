package org.anarchadia.AnvilDupe.modules;


import org.anarchadia.AnvilDupe.Addon;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.RenameItemC2SPacket;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Allows automatically duplicating items using the 1.17 anvil dupe, specifically the GoldenDupes version.
 */
public class AnvilDupe extends Module {
    /**
     * Constructor for the AnvilDupe module, setting the category and description of the module.
     */
    public AnvilDupe() {
        super(Addon.CATEGORY, "auto-anvil-dupe", "Automatically dupes using anvil dupe.");
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Integer> dupeAmount = sgGeneral.add(new IntSetting.Builder()
        .name("dupe-amount")
        .description("How many items to dupe before toggling.")
        .defaultValue(54)
        .min(1)
        .sliderMax(270)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay")
            .description("How many items to dupe before toggling.")
            .defaultValue(1)
            .min(1)
            .sliderMax(20)
            .build()
    );

    private final Setting<Integer> bottleAmount = sgGeneral.add(new IntSetting.Builder()
            .name("Amount of bottles to throw")
            .description("How many XP bottles to throw.")
            .defaultValue(1)
            .min(1)
            .sliderMax(20)
            .build()
    );

    private boolean didDupe = false;
    private int dupedCount = 0;

    /**
     * States representing the dupe process.
     */
    private enum AnvilDupeState {
        LOOKING_FOR_ANVIL,
        WAIT_FOR_GUI,
        DUPING
    }

    private AnvilDupeState currentState = AnvilDupeState.LOOKING_FOR_ANVIL;

    @Override
    public void onActivate() {
        currentState = AnvilDupeState.LOOKING_FOR_ANVIL;
        didDupe = false;
        dupedCount = 0;
    }

    @Override
    public void onDeactivate() {
        currentState = AnvilDupeState.LOOKING_FOR_ANVIL;
        didDupe = false;
        dupedCount = 0;
    }

    /**
     * Handles the TickEvent.Pre event to control the duping process stages.
     * @param event The TickEvent.Pre event triggered each tick.
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if(mc.player != null && delay.get() != 0 && mc.player.age % delay.get() != 0) return;
        if (dupedCount >= dupeAmount.get()) {
            info("Duped the desired amount of items, toggling off.");
            toggle();
            return;
        }

        switch (currentState) {
            case LOOKING_FOR_ANVIL:
                handleLookingForAnvil();
                break;
            case WAIT_FOR_GUI:
                handleWaitForGui();
                break;
            case DUPING:
                handleDuping();
                break;
        }
    }

    /**
     * Searches for an anvil in the player's vicinity, positioning the player to interact or place an anvil as needed.
     */
    private void handleLookingForAnvil() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos targetPos = playerPos.offset(mc.player.getHorizontalFacing(), 2);

        Block blockBelow = mc.world.getBlockState(targetPos.down()).getBlock();
        if (blockBelow == Blocks.AIR || blockBelow == Blocks.WATER || blockBelow == Blocks.LAVA) {
            targetPos = targetPos.down();
        }

        Block blockInFront = mc.world.getBlockState(targetPos).getBlock();
        if (isAnvil(blockInFront)) {
            ensureProperRotation(targetPos);
            Direction face = BlockUtils.getDirection(targetPos);
            BlockHitResult hitResult = new BlockHitResult(new Vec3d((double)targetPos.getX() + 0.5, (double)targetPos.getY() + 0.5, (double)targetPos.getZ() + 0.5), face, targetPos, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
            currentState = AnvilDupeState.WAIT_FOR_GUI;
        } else if (mc.world.getBlockState(targetPos).isAir()) {
            FindItemResult anvil = InvUtils.findInHotbar(AnvilDupe::isAnvil);
            if (anvil.found()) {
                BlockUtils.place(targetPos, anvil, true, 0, true);
            } else {
                error("No Anvils in hotbar, disabling");
                toggle();
            }
        }
    }


    /**
     * Ensures the player's rotation towards the anvil for interaction.
     * @param anvilPos The position of the anvil to interact with.
     */
    @SuppressWarnings("DuplicatedCode")
    private void ensureProperRotation(BlockPos anvilPos) {
        if (mc.player == null) return;
        double diffX = anvilPos.getX() + 0.5 - mc.player.getX();
        double diffY = anvilPos.getY() + 0.5 - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double diffZ = anvilPos.getZ() + 0.5 - mc.player.getZ();
        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float yaw = (float)(Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0);
        float pitch = (float)(-Math.toDegrees(Math.atan2(diffY, diffXZ)));

        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround()));
    }

    /**
     * Waits for the anvil GUI to open before proceeding to the duping process.
     */
    private void handleWaitForGui() {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        if (mc.player.currentScreenHandler instanceof AnvilScreenHandler) {
            currentState = AnvilDupeState.DUPING;
        }
    }

    /**
     * Handles the actual duping process within the anvil GUI.
     */
    private void handleDuping() {
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        if (!(mc.player.currentScreenHandler instanceof AnvilScreenHandler)) {
            currentState = AnvilDupeState.LOOKING_FOR_ANVIL;
            return;
        }
        doDupeTick();
    }

    /**
     * Performs a cycle of actions within the anvil GUI to attempt item duplication. This method carries out the following
     * key steps:

     * 1. Early exit if preconditions are not met (null checks on player, screen handler, etc.).
     * 2. Exit and disable the module if the player lacks experience levels, necessary for anvil use.
     * 3. Synchronize the item on the cursor, if any, to ensure game state consistency.
     * 4. Prepare an item for duping by placing it in the correct anvil slot and renaming it, utilizing a slight name modification
     *    to trigger the breaking mechanic.
     * 5. Attempt to pick up the resulting item from the anvil, checking for the success or failure of the duplication attempt.

     * Note: Success of this procedure depends on specific game mechanics, which may vary by version and server configuration.
     */
    private void doDupeTick() {
        if (mc.player == null || mc.player.currentScreenHandler == null || mc.interactionManager == null) return;
        if (!(mc.player.currentScreenHandler instanceof AnvilScreenHandler anvilHandler)) {
            System.out.println("Player is not in an Anvil Screen Handler");
            return;
        }

        if (mc.player.experienceLevel == 0) {
            FindItemResult exp = InvUtils.findInHotbar(Items.EXPERIENCE_BOTTLE);
            FindItemResult expI = InvUtils.find(Items.EXPERIENCE_BOTTLE);

            if (!exp.found()) return;

            Rotations.rotate(mc.player.getYaw(), 90, () -> {
                if (exp.getHand() != null) {
                    for (int i = 0; i < bottleAmount.get(); i++) {
                        mc.interactionManager.interactItem(mc.player, exp.getHand());
                    }
                }
                else {
                    InvUtils.swap(exp.slot(), true);
                    for (int i = 0; i < bottleAmount.get(); i++) {
                        mc.interactionManager.interactItem(mc.player, exp.getHand());
                    }
                    InvUtils.swapBack();
                }
            });
        }

        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            System.out.println("Synchronizing item on cursor");
            mc.interactionManager.clickSlot(anvilHandler.syncId, 30, 0, SlotActionType.PICKUP, mc.player);
        }

        if (!anvilHandler.getSlot(0).hasStack() && anvilHandler.getSlot(30).hasStack()) {
            System.out.println("Moving item from slot 30 to slot 0");
            mc.interactionManager.clickSlot(anvilHandler.syncId, 30, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(anvilHandler.syncId, 0, 0, SlotActionType.PICKUP, mc.player);
        }

        if (anvilHandler.getSlot(0).hasStack()) {
            ItemStack stackInSlot0 = anvilHandler.getSlot(0).getStack();
            String itemName = stackInSlot0.getName().getString();
            String newName = itemName.endsWith(" ") ? itemName.trim() : itemName + " ";
            System.out.println("Renaming item");
            anvilHandler.setNewItemName(newName);
            mc.player.networkHandler.sendPacket(new RenameItemC2SPacket(newName));
        }

        if (anvilHandler.getSlot(2).hasStack()) {
            System.out.println("Attempting to dupe item");
            mc.interactionManager.clickSlot(anvilHandler.syncId, 2, 0, SlotActionType.PICKUP, mc.player);

            if (anvilHandler.getSlot(2).hasStack()) {
                System.out.println("Dupe failed, resetting");
                mc.interactionManager.clickSlot(anvilHandler.syncId, 2, 0, SlotActionType.PICKUP, mc.player);

                if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
                    System.out.println("Synchronizing item on cursor");
                    mc.interactionManager.clickSlot(anvilHandler.syncId, 30, 0, SlotActionType.PICKUP, mc.player);
                }
            } else {
                System.out.println("Dupe might have succeeded");
                didDupe = true;
            }
        }
    }

    /**
     * Handles the event when the GUI screen is opened, potentially signaling the duping phase's end or continuation.
     * @param event The OpenScreenEvent indicating a new GUI screen being opened.
     */
    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (event.screen instanceof AnvilScreen) {
            currentState = AnvilDupeState.DUPING;
        }

        if (didDupe && event.screen == null) {
            info("Dupe succeeded, preparing for next round.");
            dupedCount += 1;
            didDupe = false;
            currentState = AnvilDupeState.LOOKING_FOR_ANVIL;
        }
    }

    /**
     * Checks if the given item stack represents an anvil.
     * @param is The ItemStack to be checked.
     * @return True if the item stack is an anvil, false otherwise.
     */
    private static boolean isAnvil(ItemStack is) {
        return is.getItem() == Items.DAMAGED_ANVIL || is.getItem() == Items.CHIPPED_ANVIL || is.getItem() == Items.ANVIL;
    }

    /**
     * Checks if the given block represents an anvil.
     * @param b The Block to be assessed.
     * @return True if the block is an anvil, false otherwise.
     */
    private static boolean isAnvil(Block b) {
        return b == Blocks.DAMAGED_ANVIL || b == Blocks.CHIPPED_ANVIL || b == Blocks.ANVIL;
    }
}
