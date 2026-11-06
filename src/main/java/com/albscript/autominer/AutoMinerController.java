package com.albscript.autominer;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AutoMinerController {

    // ── Cấu hình ──────────────────────────────────────────────────────────
    private static final float DURABILITY_THRESHOLD = 0.30f;
    private static final int   HUNGER_THRESHOLD     = 16;
    private static final int   BREAK_COOLDOWN       = 2;
    private static final int   ACTION_DELAY         = 5;

    // ── Frame ─────────────────────────────────────────────────────────────
    private BlockPos frameA     = null;
    private BlockPos frameB     = null;
    private int      frameCount = 0;

    private int minX, maxX, minY, maxY, minZ, maxZ;

    // ── Trạng thái ────────────────────────────────────────────────────────
    private boolean    active       = false;
    private MinerState state        = MinerState.IDLE;
    private int        cooldown     = 0;

    private BlockPos   centerTarget = null;
    private int        breakIndex   = 0;
    private Direction  mineDir      = Direction.NORTH;

    private int        currentStep  = 0;
    private int        totalSteps   = 0;

    // ── Frame Key Handler ─────────────────────────────────────────────────
    public void handleFrameKey(MinecraftClient client) {
        if (client.player == null) return;
        BlockPos pos = client.player.getBlockPos();

        if (frameCount == 0) {
            frameA = pos;
            frameCount = 1;
            AutoMinerMod.msg(client, "§eGóc A đã set: §f" + posStr(pos)
                + " §7| Bấm §eH§7 lần 2 để set Góc B");
        } else if (frameCount == 1) {
            frameB = pos;
            frameCount = 2;
            computeBounds();
            int dx = maxX - minX + 1;
            int dy = maxY - minY + 1;
            int dz = maxZ - minZ + 1;
            AutoMinerMod.msg(client, "§aKhung đã set! §f"
                + posStr(frameA) + " §7→ §f" + posStr(frameB)
                + " §7(§e" + dx + "×" + dy + "×" + dz + "§7)"
                + " | §eJ§7 bắt đầu | §eH§7 lần 3 xoá");
        } else {
            frameA     = null;
            frameB     = null;
            frameCount = 0;
            if (active) {
                active = false;
                stopMoving(client);
            }
            AutoMinerMod.msg(client, "§cĐã xoá khung. §7Bấm §eH§7 để set lại.");
        }
    }

    // ── Toggle ────────────────────────────────────────────────────────────
    public boolean isActive() { return active; }

    public void toggle(MinecraftClient client) {
        if (!active) {
            if (frameCount < 2) {
                AutoMinerMod.msg(client, "§cChưa set khung! §7Bấm §eH§7 2 lần để set 2 góc.");
                return;
            }
            active      = true;
            state       = MinerState.IDLE;
            breakIndex  = 0;
            currentStep = 0;
            mineDir     = getPlayerFacingDir(client);
            computeTotalSteps();
            AutoMinerMod.msg(client, "§aBẬT §f- Hướng: §e" + mineDir.name()
                + " §f| §e" + totalSteps + " §flayer | §eJ§f tắt");
        } else {
            active = false;
            stopMoving(client);
            AutoMinerMod.msg(client, "§cTẮT");
        }
    }

    // ── Tick chính ────────────────────────────────────────────────────────
    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        if (cooldown > 0) { cooldown--; return; }

        ClientPlayerEntity player = client.player;

        if (needsEating(player)) {
            stopMoving(client);
            state = MinerState.EATING;
            tryEat(client, player);
            cooldown = ACTION_DELAY;
            return;
        }

        if (pickaxeLow(player)) {
            stopMoving(client);
            state = MinerState.THROWING_XP;
            throwExpBottle(client, player);
            cooldown = ACTION_DELAY;
            return;
        }

        if (!hasTotemInOffhand(player)) {
            int slot = findItem(player, Items.TOTEM_OF_UNDYING);
            if (slot != -1) {
                state = MinerState.EQUIP_TOTEM;
                equipOffhand(client, player, slot);
                cooldown = ACTION_DELAY;
                return;
            }
        }

        ensurePickaxeMainHand(player);

        switch (state) {
            case IDLE           -> startNextLayer(client, player);
            case BREAKING       -> doBreak(client, player);
            case PLACING_FLOOR  -> doPlaceFloor(client, player);
            case MOVING_FORWARD -> doMove(client, player);
            case DONE           -> {
                stopMoving(client);
                active = false;
                AutoMinerMod.msg(client, "§aĐào xong toàn bộ khung!");
            }
            default -> state = MinerState.IDLE;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // LAYER LOGIC
    // ══════════════════════════════════════════════════════════════════════

    private void startNextLayer(MinecraftClient client, ClientPlayerEntity player) {
        if (currentStep >= totalSteps) {
            state = MinerState.DONE;
            return;
        }
        centerTarget = getLayerCenter(currentStep);
        if (!inBounds(centerTarget)) {
            state = MinerState.DONE;
            return;
        }
        breakIndex = 0;
        state      = MinerState.BREAKING;
        cooldown   = 1;
    }

    private BlockPos getLayerCenter(int step) {
        return switch (mineDir) {
            case NORTH -> new BlockPos((minX + maxX) / 2, (minY + maxY) / 2, maxZ - step);
            case SOUTH -> new BlockPos((minX + maxX) / 2, (minY + maxY) / 2, minZ + step);
            case EAST  -> new BlockPos(minX + step, (minY + maxY) / 2, (minZ + maxZ) / 2);
            case WEST  -> new BlockPos(maxX - step, (minY + maxY) / 2, (minZ + maxZ) / 2);
            case DOWN  -> new BlockPos((minX + maxX) / 2, maxY - step, (minZ + maxZ) / 2);
            default    -> new BlockPos((minX + maxX) / 2, (minY + maxY) / 2, maxZ - step);
        };
    }

    private BlockPos[] getLayer3x3(BlockPos center) {
        int[] dx, dy, dz;
        if (mineDir == Direction.NORTH || mineDir == Direction.SOUTH) {
            dx = new int[]{-1,-1,-1, 0, 0, 0, 1, 1, 1};
            dy = new int[]{-1, 0, 1,-1, 0, 1,-1, 0, 1};
            dz = new int[]{ 0, 0, 0, 0, 0, 0, 0, 0, 0};
        } else if (mineDir == Direction.EAST || mineDir == Direction.WEST) {
            dx = new int[]{ 0, 0, 0, 0, 0, 0, 0, 0, 0};
            dy = new int[]{-1, 0, 1,-1, 0, 1,-1, 0, 1};
            dz = new int[]{-1,-1,-1, 0, 0, 0, 1, 1, 1};
        } else {
            dx = new int[]{-1,-1,-1, 0, 0, 0, 1, 1, 1};
            dy = new int[]{ 0, 0, 0, 0, 0, 0, 0, 0, 0};
            dz = new int[]{-1, 0, 1,-1, 0, 1,-1, 0, 1};
        }
        BlockPos[] arr = new BlockPos[9];
        for (int j = 0; j < 9; j++) {
            arr[j] = clampToBounds(center.add(dx[j], dy[j], dz[j]));
        }
        return arr;
    }

    private void doBreak(MinecraftClient client, ClientPlayerEntity player) {
        ClientWorld world = client.world;
        if (world == null) return;

        BlockPos[] layer = getLayer3x3(centerTarget);

        while (breakIndex < 9 &&
              (isAirOrPassable(world.getBlockState(layer[breakIndex]).getBlock())
               || !inBounds(layer[breakIndex]))) {
            breakIndex++;
        }

        if (breakIndex >= 9) {
            breakIndex = 0;
            state      = MinerState.PLACING_FLOOR;
            cooldown   = 2;
            return;
        }

        BlockPos target = layer[breakIndex];
        Vec3d eyePos = new Vec3d(
            player.getX(),
            player.getY() + player.getEyeHeight(player.getPose()),
            player.getZ()
        );
        Vec3d targetVec = Vec3d.ofCenter(target);
        lookAt(player, targetVec);

        if (eyePos.distanceTo(targetVec) <= 5.0) {
            Direction face = getBreakFace(target, player);
            client.interactionManager.updateBlockBreakingProgress(target, face);
            client.interactionManager.attackBlock(target, face);
            breakIndex++;
            cooldown = BREAK_COOLDOWN;
        } else {
            breakIndex++;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // FLOOR PLACEMENT
    // ══════════════════════════════════════════════════════════════════════

    private void doPlaceFloor(MinecraftClient client, ClientPlayerEntity player) {
        ClientWorld world = client.world;
        if (world == null) { state = MinerState.MOVING_FORWARD; return; }

        BlockPos feet = player.getBlockPos();
        for (BlockPos fp : getFloorPositions(feet)) {
            if (!inBounds(fp)) continue;
            if (isAirOrPassable(world.getBlockState(fp).getBlock())) {
                int slot = findSolidBlock(player);
                if (slot == -1) {
                    AutoMinerMod.msg(client, "§cHết block đặt sàn!");
                    state = MinerState.MOVING_FORWARD;
                    return;
                }
                player.getInventory().setSelectedSlot(slot);
                lookDown(player);
                BlockPos support = fp.down();
                if (!isAirOrPassable(world.getBlockState(support).getBlock())) {
                    Vec3d hitVec = Vec3d.ofCenter(support).add(0, 0.5, 0);
                    client.interactionManager.interactBlock(player, Hand.MAIN_HAND,
                        new BlockHitResult(hitVec, Direction.UP, support, false));
                    cooldown = ACTION_DELAY;
                    return;
                }
            }
        }
        state    = MinerState.MOVING_FORWARD;
        cooldown = 2;
    }

    private BlockPos[] getFloorPositions(BlockPos feet) {
        BlockPos under = feet.down();
        Direction perp = getPerpendicularDir(mineDir);
        return new BlockPos[]{ under, under.offset(perp, 1), under.offset(perp, -1) };
    }

    // ══════════════════════════════════════════════════════════════════════
    // FORWARD MOVEMENT
    // ══════════════════════════════════════════════════════════════════════

    private void doMove(MinecraftClient client, ClientPlayerEntity player) {
        lookInDirection(player, mineDir);
        client.options.forwardKey.setPressed(true);

        BlockPos feet      = player.getBlockPos();
        BlockPos nextCenter = getLayerCenter(currentStep + 1);

        boolean arrived = switch (mineDir) {
            case NORTH -> feet.getZ() <= nextCenter.getZ();
            case SOUTH -> feet.getZ() >= nextCenter.getZ();
            case EAST  -> feet.getX() >= nextCenter.getX();
            case WEST  -> feet.getX() <= nextCenter.getX();
            case DOWN  -> feet.getY() <= nextCenter.getY();
            default    -> false;
        };

        if (arrived) {
            stopMoving(client);
            currentStep++;
            if (currentStep >= totalSteps) {
                state = MinerState.DONE;
            } else {
                centerTarget = getLayerCenter(currentStep);
                breakIndex   = 0;
                state        = MinerState.BREAKING;
                cooldown     = 3;
            }
        }
    }

    private void stopMoving(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
    }

    // ══════════════════════════════════════════════════════════════════════
    // EAT / XP BOTTLE / TOTEM
    // ══════════════════════════════════════════════════════════════════════

    private void tryEat(MinecraftClient client, ClientPlayerEntity player) {
        int slot = findFood(player);
        if (slot == -1) {
            AutoMinerMod.msg(client, "§cHết đồ ăn!");
            state = MinerState.IDLE;
            return;
        }
        player.getInventory().setSelectedSlot(slot);
        client.options.useKey.setPressed(true);
        cooldown = 32;
        state    = MinerState.IDLE;
    }

    private void throwExpBottle(MinecraftClient client, ClientPlayerEntity player) {
        int bottleSlot = findItem(player, Items.EXPERIENCE_BOTTLE);
        if (bottleSlot == -1) {
            AutoMinerMod.msg(client, "§cKhông có Bottle o' Enchanting!");
            state = MinerState.IDLE;
            return;
        }
        swapToOffhand(client, player, bottleSlot);
        cooldown = 3;
        player.setPitch(0f);
        client.interactionManager.interactItem(player, Hand.OFF_HAND);
        cooldown = ACTION_DELAY;
        state    = MinerState.EQUIP_TOTEM;
    }

    private void equipOffhand(MinecraftClient client, ClientPlayerEntity player, int slot) {
        swapToOffhand(client, player, slot);
    }

    // ══════════════════════════════════════════════════════════════════════
    // INVENTORY HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private void swapToOffhand(MinecraftClient client, ClientPlayerEntity player, int slot) {
        int containerSlot = (slot < 9) ? 36 + slot : slot;
        client.interactionManager.clickSlot(
            player.currentScreenHandler.syncId,
            containerSlot,
            40,
            SlotActionType.SWAP,
            player
        );
    }

    private boolean hasTotemInOffhand(ClientPlayerEntity player) {
        return player.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING;
    }

    private boolean pickaxeLow(ClientPlayerEntity player) {
        ItemStack main = player.getMainHandStack();
        if (!isPickaxe(main) || !main.isDamageable()) return false;
        int maxDur = main.getMaxDamage();
        int curDur = maxDur - main.getDamage();
        return (float) curDur / maxDur < DURABILITY_THRESHOLD;
    }

    private boolean isPickaxe(ItemStack stack) {
        Item item = stack.getItem();
        return item == Items.WOODEN_PICKAXE  || item == Items.STONE_PICKAXE
            || item == Items.IRON_PICKAXE    || item == Items.GOLDEN_PICKAXE
            || item == Items.DIAMOND_PICKAXE || item == Items.NETHERITE_PICKAXE;
    }

    private void ensurePickaxeMainHand(ClientPlayerEntity player) {
        if (!isPickaxe(player.getMainHandStack())) {
            int slot = findBestPickaxe(player);
            if (slot != -1) player.getInventory().setSelectedSlot(slot);
        }
    }

    private int findBestPickaxe(ClientPlayerEntity player) {
        Item[] priority = {
            Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE,
            Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE
        };
        for (Item pick : priority) {
            for (int i = 0; i < 9; i++) {
                if (player.getInventory().getStack(i).getItem() == pick) return i;
            }
        }
        return -1;
    }

    private boolean needsEating(ClientPlayerEntity player) {
        return player.getHungerManager().getFoodLevel() <= HUNGER_THRESHOLD;
    }

    private int findFood(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.contains(DataComponentTypes.FOOD)) return i;
        }
        return -1;
    }

    private int findItem(ClientPlayerEntity player, Item item) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).getItem() == item) return i;
        }
        for (int i = 9; i < 36; i++) {
            if (player.getInventory().getStack(i).getItem() == item) return i;
        }
        return -1;
    }

    private int findSolidBlock(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && isSolidPlaceable(stack.getItem())) return i;
        }
        return -1;
    }

    private boolean isSolidPlaceable(Item item) {
        return item == Items.COBBLESTONE      || item == Items.STONE
            || item == Items.DIRT             || item == Items.GRAVEL
            || item == Items.OAK_PLANKS       || item == Items.COBBLED_DEEPSLATE
            || item == Items.NETHERRACK       || item == Items.SAND;
    }

    // ══════════════════════════════════════════════════════════════════════
    // BOUNDS HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private void computeBounds() {
        minX = Math.min(frameA.getX(), frameB.getX());
        maxX = Math.max(frameA.getX(), frameB.getX());
        minY = Math.min(frameA.getY(), frameB.getY());
        maxY = Math.max(frameA.getY(), frameB.getY());
        minZ = Math.min(frameA.getZ(), frameB.getZ());
        maxZ = Math.max(frameA.getZ(), frameB.getZ());
    }

    private void computeTotalSteps() {
        totalSteps = switch (mineDir) {
            case NORTH, SOUTH -> maxZ - minZ + 1;
            case EAST,  WEST  -> maxX - minX + 1;
            case DOWN         -> maxY - minY + 1;
            default           -> 1;
        };
    }

    private boolean inBounds(BlockPos pos) {
        return pos.getX() >= minX && pos.getX() <= maxX
            && pos.getY() >= minY && pos.getY() <= maxY
            && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    private BlockPos clampToBounds(BlockPos pos) {
        return new BlockPos(
            Math.max(minX, Math.min(maxX, pos.getX())),
            Math.max(minY, Math.min(maxY, pos.getY())),
            Math.max(minZ, Math.min(maxZ, pos.getZ()))
        );
    }

    private String posStr(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    // ══════════════════════════════════════════════════════════════════════
    // LOOK / DIRECTION HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = new Vec3d(
            player.getX(),
            player.getY() + player.getEyeHeight(player.getPose()),
            player.getZ()
        );
        Vec3d diff  = target.subtract(eye).normalize();
        double hDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        player.setYaw((float) Math.toDegrees(Math.atan2(-diff.x, diff.z)));
        player.setPitch((float) Math.toDegrees(-Math.atan2(diff.y, hDist)));
    }

    private void lookDown(ClientPlayerEntity player) { player.setPitch(85f); }

    private void lookInDirection(ClientPlayerEntity player, Direction dir) {
        switch (dir) {
            case NORTH -> { player.setYaw(180f); player.setPitch(0f); }
            case SOUTH -> { player.setYaw(0f);   player.setPitch(0f); }
            case EAST  -> { player.setYaw(-90f);  player.setPitch(0f); }
            case WEST  -> { player.setYaw(90f);   player.setPitch(0f); }
            case DOWN  -> { player.setPitch(90f); }
            default    -> {}
        }
    }

    private Direction getBreakFace(BlockPos target, ClientPlayerEntity player) {
        Vec3d diff = new Vec3d(
            player.getX(), player.getY() + player.getEyeHeight(player.getPose()), player.getZ()
        ).subtract(Vec3d.ofCenter(target));
        if (Math.abs(diff.y) > Math.abs(diff.x) && Math.abs(diff.y) > Math.abs(diff.z))
            return diff.y > 0 ? Direction.UP : Direction.DOWN;
        if (Math.abs(diff.x) > Math.abs(diff.z))
            return diff.x > 0 ? Direction.EAST : Direction.WEST;
        return diff.z > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private Direction getPlayerFacingDir(MinecraftClient client) {
        if (client.player == null) return Direction.NORTH;
        return client.player.getHorizontalFacing();
    }

    private Direction getPerpendicularDir(Direction dir) {
        return switch (dir) {
            case NORTH, SOUTH -> Direction.EAST;
            case EAST,  WEST  -> Direction.NORTH;
            default           -> Direction.EAST;
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // BLOCK HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private boolean isAirOrPassable(Block block) {
        return block == Blocks.AIR
            || block == Blocks.CAVE_AIR
            || block == Blocks.VOID_AIR
            || block == Blocks.WATER
            || block == Blocks.LAVA
            || block == Blocks.SHORT_GRASS
            || block == Blocks.TALL_GRASS
            || block == Blocks.VINE
            || block == Blocks.SNOW;
    }
}
