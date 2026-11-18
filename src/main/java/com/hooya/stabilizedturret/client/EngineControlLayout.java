package com.hooya.stabilizedturret.client;

/** Layout in Minecraft's scaled GUI coordinates, independent of physical resolution. */
public record EngineControlLayout(int left, int top, int width, int height, int listOffset, boolean compact, int footerHeight) {
    public static final int CARD_HEIGHT = 122;
    public static final int CARD_GAP = 6;
    public static final int LIST_TOP = 110;
    public static final int FOOTER_HEIGHT = 42;

    public static EngineControlLayout calculate(int screenWidth, int screenHeight, int behaviorCount) {
        return calculate(screenWidth, screenHeight, behaviorCount, false);
    }
    public static EngineControlLayout calculate(int screenWidth, int screenHeight, int behaviorCount, boolean soundRpmEnabled) {
        boolean compact = screenHeight < 220;
        int listOffset = LIST_TOP + (soundRpmEnabled ? 22 : 0) - (compact ? 20 : 0);
        int footer = screenHeight < 300 ? 28 : FOOTER_HEIGHT;
        int width = Math.max(1, Math.min(320, screenWidth - 12));
        int content = behaviorCount * (CARD_HEIGHT + CARD_GAP) - CARD_GAP;
        int height = Math.max(1, Math.min(Math.min(520, screenHeight - 12), listOffset + content + footer));
        return new EngineControlLayout((screenWidth - width) / 2, (screenHeight - height) / 2, width, height, listOffset, compact, footer);
    }
    public int right() { return left + width; }
    public int bottom() { return top + height; }
    public int settingsTop() { return top + (compact ? 24 : 44); }
    public int behaviorHeaderTop() { return listTop() - 22; }
    public int listTop() { return top + listOffset; }
    public int listBottom() { return Math.max(listTop() + 1, bottom() - footerHeight); }
    public int listHeight() { return listBottom() - listTop(); }
    public int cardLeft() { return left + 10; }
    public int cardWidth() { return width - 28; }
    public int columnWidth() { return Math.max(1, (cardWidth() - 58) / 3); }
    public int columnX(int column) { return cardLeft() + 42 + column * (columnWidth() + 4); }
    public int contentHeight(int count) { return count * (CARD_HEIGHT + CARD_GAP) - CARD_GAP; }
    public int maxScroll(int count) { return Math.max(0, contentHeight(count) - listHeight()); }
}
