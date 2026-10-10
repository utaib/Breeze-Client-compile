package dev.breeze;

public enum Category {
    HUD("HUD"),
    VISUAL("Visual"),
    UTILITY("Utility"),
    CHAT("Chat"),
    PERFORMANCE("Performance"),
    PVP("PvP");

    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }

    /**
     * The label shown in the UI. Never render {@link #name()}: it is SCREAMING
     * CASE and produced subtitles like "PERFORMANCE" on cards sitting directly
     * under Title Case sidebar tabs for the same category. Both the tab list and
     * the card subtitle read this, so the two cannot drift apart.
     */
    public String displayName() {
        return displayName;
    }
}
