package com.albscript.autominer;

public enum MinerState {
    IDLE,
    WALK_TO_BLOCK,   // đi đến block cần đào
    BREAKING,        // đang phá block
    DESCEND,         // đào xuống 3 ô để xuống tầng
    PLACING_FLOOR,   // đặt sàn nếu hổng
    EATING,
    THROWING_XP,
    EQUIP_TOTEM,
    DONE
}
