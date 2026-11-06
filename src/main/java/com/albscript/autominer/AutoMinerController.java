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
    // H lần 1 → frameA, H lần 2 → frameB, H lần 3 → reset cả 2
    private BlockPos frameA   = null;
    private BlockPos frameB   = null;
    private int      frameCount = 0; // 0=chưa set, 1=có A, 2=có A+B

    private int minX, maxX, minY, maxY, minZ, maxZ;

    // ── Trạng thái ────────────────────────────────────────────────────────
    private boolean    active       = false;
    private MinerState state        = MinerState.IDLE;
    private int        cooldown     = 0;

    private BlockPos   centerTarget = null;
    private int        breakIndex   = 0;
    private Direction  mineDir      = Direction.NORTH;

    // Vị trí layer hiện tại dọc theo trục đào
    private int        currentStep  = 0;
    private int        totalSteps   = 0;

    // ── Frame Key Handler ─────────────────────────────────────────────────
    public void handleFrameKey(MinecraftClient client) {
        if (client.player == null) return;
        BlockPos pos = client.player.getBlockPos();

        if (frameCount == 0) {
            // Lần 1: set góc A
            frameA = pos;
            frameCount = 1;
            AutoMinerMod.msg(client, "§eGóc A đã set: §f" + posStr(pos)
                + " §7| Bấm §eH§7 lần 2 để set Góc B");
        } else if (frameCount == 1) {
            // Lần 2: set góc B → tính bounding box
            frameB = pos;
            frameCount = 2;
            computeBounds();
            int dx = maxX - minX + 1;
            int dy = maxY - minY + 1;
            int dz = maxZ - minZ + 1;
            AutoMinerMod.msg(client, "§aKhung đã set! §f" + posStr(frameA)
                + " §7→ §f" + posStr(frameB)
                + " §7(§e" + dx + "×" + dy + "×" + dz + "§7)"
                + " | §eJ§7 để bắt đầu đào | §eH§7 lần 3 để xoá");
        } else {
            // Lần 3: reset tất cả
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
            active       = true;
            state        = MinerState.IDLE;
            breakIndex   = 0;
            currentStep  = 0;
            mineDir      = getPlayerFacingDir(client);
            computeTotalSteps();
            AutoMinerMod.msg(client, "§aBẬT §f- Hướng: §e" + mineDir.name()
                + " §f| §e" + totalSteps + " §flayer | §eJ§f để tắt");
        } else {
            active = false;
            stopMoving(client);
            AutoMinerMod.msg(client, "§cTẮT");
        }
    }

    // ── Tick ──────────────────────────────────────────────────────────────
    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        if (cooldown > 0) { cooldown--; return; }

        ClientPlayerEntity player = client.player;

        // Priority 1: đói → ăn
        if (needsEating(player)) {
            stopMoving(client);
            state = MinerState.EATING;
            tryEat(client, player);
            cooldown = ACTION_DELAY;
            return;
        }

        // Priority 2: cúp sắp hỏng → ném EXP bottle
        if (pickaxeLow(player)) {
            stopMoving(client);
            state = MinerState.THROWING_XP;
            throwExpBottle(client, player);
            cooldown = ACTION_DELAY;
            return;
        }

        // Priority 3: tay phụ không phải totem → equip
        if (!hasTotemInOffhand(player)) {
            int slot = findItem(player, Items.TOTEM_OF_UNDYING);
            if (slot != -1) {
                state = MinerState.EQUIP_TOTEM;
                equipOffhand(client, player, slot);
                cooldown = ACTION_DELAY;
                return;
            }
        }

        // Priority 4: tay chính phải là cúp
        ensurePickaxeMainHand(player);

        // Main logic
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
    // LAYER LOGIC (giới hạn trong khung)
    // ══════════════════════════════════════════════════════════════════════

    private void startNextLayer(MinecraftClient client, ClientPlayerEntity player) {
        if (currentStep >= totalSteps) {
            state = MinerState.DONE;
            return;
        }

        // Tính center block của layer theo step
        centerTarget = getLayerCenter(currentStep);

        // Kiểm tra center có trong khung không
        if (!inBounds(centerTarget)) {
            state = MinerState.DONE;
            return;
        }

        breakIndex = 0;
        state      = MinerState.BREAKING;
        cooldown   = 1;
    }

    /** Lấy center block của layer thứ `step` tính từ điểm bắt đầu */
    private BlockPos getLayerCenter(int step) {
        BlockPos playerPos = null;
        // Start từ min theo hướng đào
        switch (mineDir) {
            case NORTH -> { return new BlockPos(
                (minX + maxX) / 2,
                (minY + maxY) / 2,
                maxZ - step); }
            case SOUTH -> { return new BlockPos(
                (minX + maxX) / 2,
                (minY + maxY) / 2,
                minZ + step); }
            case EAST -> { return new BlockPos(
                minX + step,
                (minY + maxY) / 2,
                (minZ + maxZ) / 2); }
            case WEST -> { return new BlockPos(
                maxX - step,
                (minY + maxY) / 2,
                (minZ + maxZ) / 2); }
            case DOWN -> { return new BlockPos(
                (minX + maxX) / 2,
                maxY - step,
                (minZ + maxZ) / 2); }
            default -> { return new BlockPos(
                (minX + maxX) / 2,
                (minY + maxY) / 2,
                maxZ - step); }
        }
    }

    /** Lấy 9 block của layer 3×3, chỉ trả về những block trong khung */
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
            // DOWN
            dx = new int[]{-1,-1,-1, 0, 0, 0, 1, 1, 1};
            dy = new int[]{ 0, 0, 0, 0, 0, 0, 0, 0, 0};
            dz = new int[]{-1, 0, 1,-1, 0, 1,-1, 0, 1};
        }
        BlockPos[] arr = new BlockPos[9];
        for (int j = 0; j < 9; j++) {
            BlockPos bp = center.add(dx[j], dy[j], dz[j]);
            // Clamp vào trong khung — không đào ra ngoài
            arr[j] = clampToBounds(bp);
        }
        return arr;
    }

    private void doBreak(MinecraftClient client, ClientPlayerEntity player) {
        ClientWorld world = client.world;
        if (world == null) return;

        BlockPos[] layer = getLayer3x3(centerTarget);

        // Skip block đã air hoặc ngoài khung
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
        BlockPos[] floorBlocks = getFloorPositions(feet);

        for (BlockPos fp : floorBlocks) {
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
                BlockPos supportPos = fp.down();
                if (!isAirOrPassable(world.getBlockState(supportPos).getBlock())) {
                    Vec3d hitVec = Vec3d.ofCenter(supportPos).add(0, 0.5, 0);
                    BlockHitResult hitResult = new BlockHitResult(
                        hitVec, Direction.UP, supportPos, false
                    );
                    client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
                    cooldown = ACTION_DELAY;
                    return;
                }
            }
        }

        state    = MinerState.MOVING_FORWARD;
        cooldown = 2;
    }

    private BlockPos[] getFloorPositions(BlockPos feet) {
        BlockPos underFeet = feet.down();
        Direction perp = getPerpendicularDir(mineDir);
        return new BlockPos[]{
            underFeet,
            underFeet.offset(perp, 1),
            underFeet.offset(perp, -1)
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // FORWARD MOVEMENT
    // ══════════════════════════════════════════════════════════════════════

    private void doMove(MinecraftClient client, ClientPlayerEntity player) {
        lookInDirection(player, mineDir);
        client.options.forwardKey.setPressed(true);

        BlockPos feet = player.getBlockPos();
        BlockPos nextCenter = getLayerCenter(currentStep + 1);

        // Khi đã tiến đủ 1 bước
        if (feet.getX() == nextCenter.getX() && feet.getZ() == nextCenter.getZ()
            || (mineDir == Direction.DOWN && feet.getY() <= nextCenter.getY())) {
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
    // EXP BOTTLE / TOTEM / EAT
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
        cooldown = 32; // giữ đủ lâu để ăn xong
        state    = MinerState.IDLE;
    }

    private void throwExpBottle(MinecraftClient client, ClientPlayerEntity player) {
        int bottleSlot = findItem(player, Items.EXPERIENCE_BOTTLE);
        if (bottleSlot == -1) {
            AutoMinerMod.msg(client, "§cKhông có Bottle o' Enchanting!");
            state = MinerState.IDLE;
            return;
        }
        // Đặt bottle vào off-hand
        swapToOffhand(client, player, bottleSlot);
        cooldown = 3;
        // Ném
        player.setPitch(0f);
        client.interactionManager.interactItem(player, Hand.OFF_HAND);
        cooldown = ACTION_DELAY;
        // Sau đó equip totem
        state = MinerState.EQUIP_TOTEM;
    }

    private void equipOffhand(MinecraftClient client, ClientPlayerEntity player, int slot) {
        swapToOffhand(client, player, slot);
    }

    // ══════════════════════════════════════════════════════════════════════
    // INVENTORY HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private void swapToOffhand(MinecraftClient client, ClientPlayerEntity player, int slot) {
        int containerSlot;
        if (slot < 9) {
            // Hotbar: map sang container slot 36-44
            containerSlot = 36 + slot;
        } else {
            // Main inventory: map sang container slot 9-35
            containerSlot = slot;
        }
        client.interactionManager.clickSlot(
            player.currentScreenHandler.syncId,
            containerSlot,
            40, // F key = swap with offhand
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
        return item == Items.WOODEN_PICKAXE || item == Items.STONE_PICKAXE
            || item == Items.IRON_PICKAXE   || item == Items.GOLDEN_PICKAXE
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
        // Hotbar dưới
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).getItem() == item) return i;
        }
        // Main inventory
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
        return item == Items.COBBLESTONE     || item == Items.STONE
            || item == Items.DIRT            || item == Items.GRAVEL
            || item == Items.OAK_PLANKS      || item == Items.COBBLED_DEEPSLATE
            || item == Items.NETHERRACK      || item == Items.SAND;
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
        switch (mineDir) {
            case NORTH, SOUTH -> totalSteps = maxZ - minZ + 1;
            case EAST,  WEST  -> totalSteps = maxX - minX + 1;
            case DOWN         -> totalSteps = maxY - minY + 1;
            default           -> totalSteps = 1;
        }
    }

    private boolean inBounds(BlockPos pos) {
        return pos.getX() >= minX && pos.getX() <= maxX
            && pos.getY() >= minY && pos.getY() <= maxY
            && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    /** Giới hạn block về trong khung */
    private BlockPos clampToBounds(BlockPos pos) {
        int x = Math.max(minX, Math.min(maxX, pos.getX()));
        int y = Math.max(minY, Math.min(maxY, pos.getY()));
        int z = Math.max(minZ, Math.min(maxZ, pos.getZ()));
        return new BlockPos(x, y, z);
    }

    private String posStr(BlockPos p) {
        return "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
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
        Vec3d diff = target.subtract(eye).normalize();
        double hDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float yaw   = (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(diff.y, hDist));
        player.setYaw(yaw);
        player.setPitch(pitch);
    }

    private void lookDown(ClientPlayerEntity player) {
        player.setPitch(85f);
    }

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
        Vec3d eye = new Vec3d(
            player.getX(),
            player.getY() + player.getEyeHeight(player.getPose()),
            player.getZ()
        );
        Vec3d diff = eye.subtract(Vec3d.ofCenter(target));
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
            case EAST, WEST   -> Direction.NORTH;
            default           -> Direction.EAST;
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    // BLOCK HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private boolean isAirOrPassable(Block block) {
        return block == Blocks.AIR       || block == Blocks.CAVE_AIR
            || block == Blocks.VOID_AIR  || block == Blocks.WATER
            || block == Blocks.LAVA      || block == Blocks.GRASS
            || block == Blocks.TALL_GRASS || block == Blocks.VINE
            || block == Blocks.SNOW;
    }
}
