package com.hooya.stabilizedturret.content.controller;

import net.minecraft.network.chat.Component;

public enum BindingSlot {
    BEARING("unassigned physical bearing", "\u672a\u5206\u914d\u7269\u7406\u8f74\u627f"),
    YAW("direction bearing", "\u65b9\u5411\u673a"),
    PITCH("elevation bearing", "\u9ad8\u4f4e\u673a"),
    SEAT("operator seat", "\u64cd\u4f5c\u5ea7\u6905"),
    CANNON_MOUNT("cannon mount", "\u706b\u70ae\u5e95\u5ea7"),
    MISSILE_LAUNCHER("anti-tank missile launcher", "\u53cd\u5766\u514b\u5bfc\u5f39\u53d1\u5c04\u5668"),
    LASER_DESIGNATOR("laser designator", "\u6fc0\u5149\u5236\u5bfc\u5668"),
    RADAR_BEARING("radar bearing", "\u96f7\u8fbe\u8f74\u627f"),
    AA_MISSILE_LAUNCHER("air-defense missile launcher", "\u9632\u7a7a\u5bfc\u5f39\u53d1\u5c04\u5668"),
    AIRCRAFT_MISSILE_RACK("aircraft missile rack", "\u673a\u8f7d\u5bfc\u5f39\u6302\u67b6"),
    AIRCRAFT_RADAR("aircraft radar", "\u673a\u8f7d\u96f7\u8fbe"),
    POD("electro-optical pod", "\u5149\u7535\u540a\u8231"),
    POD_YAW("pod direction bearing", "\u540a\u8231\u65b9\u5411\u673a"),
    POD_PITCH("pod elevation bearing", "\u540a\u8231\u9ad8\u4f4e\u673a"),
    REMOTE_CONTROLLER_HUB("remote controller terminal", "\u666e\u901a\u9065\u63a7\u5668\u7ec8\u7aef"),
    TWEAKED_CONTROLLER_HUB("advanced controller terminal", "\u9ad8\u7ea7\u9065\u63a7\u5668\u7ec8\u7aef");

    private final String englishName;
    private final String chineseName;

    BindingSlot(String englishName, String chineseName) {
        this.englishName = englishName;
        this.chineseName = chineseName;
    }

    public BindingSlot next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public Component displayName() {
        return Component.literal(chineseName + " / " + englishName);
    }
}