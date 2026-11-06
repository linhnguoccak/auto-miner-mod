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
    private static final int   BREAK_COOLDOWN       = 6;
    private static final int   ACTION_DELAY         = 5;

    // ── Frame ─────────────────────────────────────────────────────────────
    private BlockPos frameA     = null;
    private BlockPos frameB     = null;
    private int      frameCount = 0;
    private int      minX, maxX, minY, maxY, minZ, maxZ;

    // ── Trạng thái ────────────────────────────────────────────────────────
    private boolean    active       = false;
    private MinerState state        = MinerState.IDLE;
    private int        cooldown     = 0;

    private Direction  mineDir      = Direction.NORTH;

    // Lớp ngang hiện tại (0 = lớp trên cùng, tăng dần xuống)
    // Mỗi lớp cao 3 block (đủ để đi lại)
    private int        currentFloor  = 0;
    private int        totalFloors   = 0;

    // Bước dọc theo hướng đào trong lớp hiện tại
    private int        currentStep   = 0;
    private int        totalSteps    = 0;

    private BlockPos   standPos      = null;
    private BlockPos   targetBlock   = null;

    // ── Public API ────────────────────────────────────────────────────────
    public boolean  isActive()      { return active; }
    public boolean  hasFrame()      { return frameCount >= 1; }
    public int      getFrameCount() { return frameCount; }
    public BlockPos getFrameA()     { return frameA; }
    public BlockPos getFrameB()     { return frameB; }

    // ── Frame Key ─────────────────────────────────────────────────────────
    public void handleFrameKey(MinecraftClient client) {
        if (client.player == null) return;
        BlockPos pos = client.player.getBlockPos();
        if (frameCount == 0) {
            frameA = pos; frameCount = 1;
            AutoMinerMod.msg(client, "§eGóc A: §f" + posStr(pos)
                + " §7| §eH§7 lần 2 set Góc B");
        } else if (frameCount == 1) {
            frameB = pos; frameCount = 2;
            computeBounds();
            AutoMinerMod.msg(client, "§aKhung OK! §f"
                + posStr(frameA) + "§7→§f" + posStr(frameB)
                + " §7(§e"+(maxX-minX+1)+"×"+(maxY-minY+1)+"×"+(maxZ-minZ+1)+"§7)"
                + " | §eJ§7 đào | §eH§7×3 xoá");
        } else {
            frameA = null; frameB = null; frameCount = 0;
            if (active) { active = false; stopAll(client); }
            AutoMinerMod.msg(client, "§cĐã xoá khung.");
        }
    }

    // ── Toggle ────────────────────────────────────────────────────────────
    public void toggle(MinecraftClient client) {
        if (!active) {
            if (frameCount < 2) {
                AutoMinerMod.msg(client, "§cChưa set khung! §eH§7 2 lần.");
                return;
            }
            active       = true;
            currentFloor = 0;
            currentStep  = 0;
            mineDir      = getPlayerFacingDir(client);
            computeTotalFloors();
            computeTotalSteps();
            computeCurrentLayer();
            state        = MinerState.WALK_TO_BLOCK;
            AutoMinerMod.msg(client, "§aBẬT | §ehướng §f" + mineDir.name()
                + " §e| " + totalSteps + " §fbước §e| " + totalFloors + " §ftầng");
        } else {
            active = false;
            stopAll(client);
            AutoMinerMod.msg(client, "§cTẮT");
        }
    }

    // ── Tick chính ────────────────────────────────────────────────────────
    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        if (cooldown > 0) { cooldown--; return; }

        ClientPlayerEntity player = client.player;

        // Interrupts
        if (needsEating(player)) {
            stopAll(client);
            tryEat(client, player);
            cooldown = 32;
            return;
        }
        if (pickaxeLow(player)) {
            stopAll(client);
            throwExpBottle(client, player);
            cooldown = ACTION_DELAY;
            return;
        }
        if (!hasTotemInOffhand(player)) {
            int s = findItem(player, Items.TOTEM_OF_UNDYING);
            if (s != -1) { equipOffhand(client, player, s); cooldown = ACTION_DELAY; return; }
        }
        ensurePickaxeMainHand(player);

        switch (state) {
            case WALK_TO_BLOCK  -> doWalkToStand(client, player);
            case BREAKING       -> doBreakCenter(client, player);
            case MOVING_FORWARD -> doAdvance(client, player);
            case DESCEND        -> doDescend(client, player);
            case DONE           -> {
                stopAll(client);
                active = false;
                AutoMinerMod.msg(client, "§aXong! Đã đào hết khung.");
            }
            default -> state = MinerState.WALK_TO_BLOCK;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // WALK TO STAND POSITION
    // ══════════════════════════════════════════════════════════════════════
    private void doWalkToStand(MinecraftClient client, ClientPlayerEntity player) {
        if (standPos == null) { state = MinerState.DONE; return; }

        Vec3d pv   = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d sv   = Vec3d.ofCenter(standPos);
        double dist = Math.sqrt(
            (pv.x - sv.x)*(pv.x - sv.x) + (pv.z - sv.z)*(pv.z - sv.z)
        );

        if (dist < 0.5) {
            stopMoving(client);
            state    = MinerState.BREAKING;
            cooldown = 2;
            return;
        }

        lookAt(player, sv);
        client.options.forwardKey.setPressed(true);
        client.options.jumpKey.setPressed(standPos.getY() > player.getBlockPos().getY());
    }

    // ══════════════════════════════════════════════════════════════════════
    // BREAK CENTER — đào 1 cú, cúp 3×3 phá hết layer
    // ══════════════════════════════════════════════════════════════════════
    private void doBreakCenter(MinecraftClient client, ClientPlayerEntity player) {
        if (targetBlock == null) { state = MinerState.MOVING_FORWARD; return; }

        lookAt(player, Vec3d.ofCenter(targetBlock));
        Direction face = getBreakFace(targetBlock, player);
        client.interactionManager.updateBlockBreakingProgress(targetBlock, face);
        client.interactionManager.attackBlock(targetBlock, face);

        state    = MinerState.MOVING_FORWARD;
        cooldown = BREAK_COOLDOWN;
    }

    // ══════════════════════════════════════════════════════════════════════
    // ADVANCE — tiến 1 bước, nếu hết bước thì xuống tầng
    // ══════════════════════════════════════════════════════════════════════
    private void doAdvance(MinecraftClient client, ClientPlayerEntity player) {
        currentStep++;

        if (currentStep >= totalSteps) {
            // Hết lớp ngang → xuống tầng
            currentFloor++;
            if (currentFloor >= totalFloors) {
                state = MinerState.DONE;
                return;
            }
            // Reset bước cho tầng mới
            currentStep = 0;
            state       = MinerState.DESCEND;
            cooldown    = 3;
            AutoMinerMod.msg(client, "§6Xong tầng §e" + currentFloor
                + "§6/§e" + totalFloors + "§6 — đào xuống...");
        } else {
            computeCurrentLayer();
            state    = MinerState.WALK_TO_BLOCK;
            cooldown = 3;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // DESCEND — đào xuống 3 ô rồi đến stand pos tầng mới
    // ══════════════════════════════════════════════════════════════════════
    private void doDescend(MinecraftClient client, ClientPlayerEntity player) {
        ClientWorld world = client.world;
        if (world == null) return;

        // Tính Y đáy của tầng mới
        int floorTopY = maxY - currentFloor * 3;    // Y trần của tầng mới
        int standY    = floorTopY - 1;              // Y player sẽ đứng

        // Đào block dưới chân cho đến khi trống xuống standY
        for (int i = 1; i <= 3; i++) {
            BlockPos below = player.getBlockPos().down(i);
            if (below.getY() < minY) { state = MinerState.DONE; return; }
            if (!isAirOrPassable(world.getBlockState(below).getBlock())) {
                // Nhìn xuống và đào
                player.setPitch(89f);
                client.interactionManager.updateBlockBreakingProgress(below, Direction.UP);
                client.interactionManager.attackBlock(below, Direction.UP);
                cooldown = BREAK_COOLDOWN;
                return;
            }
        }

        // Đã đào xong 3 ô → kiểm tra player đã rơi xuống chưa
        if (player.getY() > standY + 1.0) {
            // Nhích về phía lỗ để rơi xuống
            client.options.sneakKey.setPressed(true);
            lookInDirection(player, mineDir);
            cooldown = 2;
        } else {
            // Đã xuống đủ
            client.options.sneakKey.setPressed(false);
            computeCurrentLayer();
            state    = MinerState.WALK_TO_BLOCK;
            cooldown = 5;
            AutoMinerMod.msg(client, "§aTầng §e" + (currentFloor + 1)
                + "§a/§e" + totalFloors);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // LAYER COMPUTATION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Tính standPos và targetBlock cho (currentFloor, currentStep).
     *
     * Mỗi tầng (floor) cao 3 block.
     * floorTopY = maxY - floor*3        → Y trần của tầng
     * standY    = floorTopY - 1         → Y player đứng (chân ở đây, đầu ở floorTopY)
     * centerY   = standY + 1            → Y ngang mắt (block giữa của tường 3×3)
     *
     * standPos  = lùi lại 1 block so với tường
     * targetBlock = block CENTER của tường
     */
    private void computeCurrentLayer() {
        int floorTopY = maxY - currentFloor * 3;
        int standY    = floorTopY - 1;
        int centerY   = standY + 1; // ngang mắt player

        int cx = (minX + maxX) / 2;
        int cz = (minZ + maxZ) / 2;

        switch (mineDir) {
            case NORTH -> {
                int wallZ   = maxZ - currentStep;
                targetBlock = new BlockPos(cx, centerY, wallZ);
                standPos    = new BlockPos(cx, standY, wallZ + 1);
            }
            case SOUTH -> {
                int wallZ   = minZ + currentStep;
                targetBlock = new BlockPos(cx, centerY, wallZ);
                standPos    = new BlockPos(cx, standY, wallZ - 1);
            }
            case EAST -> {
                int wallX   = minX + currentStep;
                targetBlock = new BlockPos(wallX, centerY, cz);
                standPos    = new BlockPos(wallX - 1, standY, cz);
            }
            case WEST -> {
                int wallX   = maxX - currentStep;
                targetBlock = new BlockPos(wallX, centerY, cz);
                standPos    = new BlockPos(wallX + 1, standY, cz);
            }
            default -> { targetBlock = null; standPos = null; }
        }

        if (targetBlock != null && !inBounds(targetBlock)) {
            targetBlock = null; standPos = null;
            state = MinerState.DONE;
        }
    }

    private void computeTotalSteps() {
        totalSteps = switch (mineDir) {
            case NORTH, SOUTH -> maxZ - minZ + 1;
            case EAST,  WEST  -> maxX - minX + 1;
            default           -> 1;
        };
    }

    private void computeTotalFloors() {
        // Mỗi tầng = 3 block Y
        int height = maxY - minY + 1;
        totalFloors = Math.max(1, (height + 2) / 3); // ceil(height/3)
    }

    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════
    private void stopAll(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        client.options.useKey.setPressed(false);
    }

    private void stopMoving(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }

    private void tryEat(MinecraftClient client, ClientPlayerEntity player) {
        int slot = findFood(player);
        if (slot == -1) { AutoMinerMod.msg(client, "§cHết đồ ăn!"); return; }
        player.getInventory().setSelectedSlot(slot);
        client.options.useKey.setPressed(true);
    }

    private void throwExpBottle(MinecraftClient client, ClientPlayerEntity player) {
        int slot = findItem(player, Items.EXPERIENCE_BOTTLE);
        if (slot == -1) { AutoMinerMod.msg(client, "§cKhông có EXP Bottle!"); return; }
        swapToOffhand(client, player, slot);
        cooldown = 3;
        player.setPitch(0f);
        client.interactionManager.interactItem(player, Hand.OFF_HAND);
        int totem = findItem(player, Items.TOTEM_OF_UNDYING);
        if (totem != -1) equipOffhand(client, player, totem);
    }

    private void equipOffhand(MinecraftClient client, ClientPlayerEntity player, int slot) {
        swapToOffhand(client, player, slot);
    }

    private void swapToOffhand(MinecraftClient client, ClientPlayerEntity player, int slot) {
        int cs = (slot < 9) ? 36 + slot : slot;
        client.interactionManager.clickSlot(
            player.currentScreenHandler.syncId, cs, 40, SlotActionType.SWAP, player);
    }

    private boolean hasTotemInOffhand(ClientPlayerEntity p) {
        return p.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING;
    }

    private boolean pickaxeLow(ClientPlayerEntity p) {
        ItemStack m = p.getMainHandStack();
        if (!isPickaxe(m) || !m.isDamageable()) return false;
        return (float)(m.getMaxDamage() - m.getDamage()) / m.getMaxDamage() < DURABILITY_THRESHOLD;
    }

    private boolean isPickaxe(ItemStack s) {
        Item i = s.getItem();
        return i==Items.WOODEN_PICKAXE||i==Items.STONE_PICKAXE||i==Items.IRON_PICKAXE
            ||i==Items.GOLDEN_PICKAXE||i==Items.DIAMOND_PICKAXE||i==Items.NETHERITE_PICKAXE;
    }

    private void ensurePickaxeMainHand(ClientPlayerEntity p) {
        if (!isPickaxe(p.getMainHandStack())) {
            int s = findBestPickaxe(p);
            if (s != -1) p.getInventory().setSelectedSlot(s);
        }
    }

    private int findBestPickaxe(ClientPlayerEntity p) {
        Item[] pr = {Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE,
                     Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE};
        for (Item pick : pr)
            for (int i = 0; i < 9; i++)
                if (p.getInventory().getStack(i).getItem()==pick) return i;
        return -1;
    }

    private boolean needsEating(ClientPlayerEntity p) {
        return p.getHungerManager().getFoodLevel() <= HUNGER_THRESHOLD;
    }

    private int findFood(ClientPlayerEntity p) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.isEmpty() && s.contains(DataComponentTypes.FOOD)) return i;
        }
        return -1;
    }

    private int findItem(ClientPlayerEntity p, Item item) {
        for (int i = 0; i < 36; i++)
            if (p.getInventory().getStack(i).getItem()==item) return i;
        return -1;
    }

    private int findSolidBlock(ClientPlayerEntity p) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.isEmpty() && isSolidPlaceable(s.getItem())) return i;
        }
        return -1;
    }

    private boolean isSolidPlaceable(Item i) {
        return i==Items.COBBLESTONE||i==Items.STONE||i==Items.DIRT
            ||i==Items.GRAVEL||i==Items.OAK_PLANKS||i==Items.COBBLED_DEEPSLATE
            ||i==Items.NETHERRACK||i==Items.SAND;
    }

    private void computeBounds() {
        minX = Math.min(frameA.getX(), frameB.getX());
        maxX = Math.max(frameA.getX(), frameB.getX());
        minY = Math.min(frameA.getY(), frameB.getY());
        maxY = Math.max(frameA.getY(), frameB.getY());
        minZ = Math.min(frameA.getZ(), frameB.getZ());
        maxZ = Math.max(frameA.getZ(), frameB.getZ());
    }

    private boolean inBounds(BlockPos p) {
        return p.getX()>=minX&&p.getX()<=maxX
            &&p.getY()>=minY&&p.getY()<=maxY
            &&p.getZ()>=minZ&&p.getZ()<=maxZ;
    }

    private String posStr(BlockPos p) {
        return "("+p.getX()+","+p.getY()+","+p.getZ()+")";
    }

    private void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = new Vec3d(
            player.getX(),
            player.getY() + player.getEyeHeight(player.getPose()),
            player.getZ()
        );
        Vec3d d = target.subtract(eye).normalize();
        double h = Math.sqrt(d.x*d.x + d.z*d.z);
        player.setYaw((float)Math.toDegrees(Math.atan2(-d.x, d.z)));
        player.setPitch((float)Math.toDegrees(-Math.atan2(d.y, h)));
    }

    private void lookInDirection(ClientPlayerEntity player, Direction dir) {
        switch (dir) {
            case NORTH -> { player.setYaw(180f); player.setPitch(0f); }
            case SOUTH -> { player.setYaw(0f);   player.setPitch(0f); }
            case EAST  -> { player.setYaw(-90f);  player.setPitch(0f); }
            case WEST  -> { player.setYaw(90f);   player.setPitch(0f); }
            default    -> {}
        }
    }

    private Direction getBreakFace(BlockPos target, ClientPlayerEntity player) {
        Vec3d d = new Vec3d(
            player.getX(),
            player.getY() + player.getEyeHeight(player.getPose()),
            player.getZ()
        ).subtract(Vec3d.ofCenter(target));
        if (Math.abs(d.y)>Math.abs(d.x)&&Math.abs(d.y)>Math.abs(d.z))
            return d.y>0 ? Direction.UP : Direction.DOWN;
        if (Math.abs(d.x)>Math.abs(d.z))
            return d.x>0 ? Direction.EAST : Direction.WEST;
        return d.z>0 ? Direction.SOUTH : Direction.NORTH;
    }

    private Direction getPlayerFacingDir(MinecraftClient client) {
        if (client.player == null) return Direction.NORTH;
        return client.player.getHorizontalFacing();
    }

    private boolean isAirOrPassable(Block block) {
        return block==Blocks.AIR||block==Blocks.CAVE_AIR||block==Blocks.VOID_AIR
            ||block==Blocks.WATER||block==Blocks.LAVA
            ||block==Blocks.SHORT_GRASS||block==Blocks.TALL_GRASS
            ||block==Blocks.VINE||block==Blocks.SNOW;
    }
    }
