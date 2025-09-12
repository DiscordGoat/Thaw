package goat.thaw.system.effects;

class ActiveEffect {
    private final EffectId id;
    private int level; // 1..n
    private Integer remainingSeconds; // null for circumstantial (no timer)
    private final boolean circumstantial;

    ActiveEffect(EffectId id, int level, Integer remainingSeconds, boolean circumstantial) {
        this.id = id;
        this.level = Math.max(level, 0);
        this.remainingSeconds = remainingSeconds;
        this.circumstantial = circumstantial;
    }

    public EffectId getId() { return id; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = Math.max(level, 0); }
    public Integer getRemainingSeconds() { return remainingSeconds; }
    public void setRemainingSeconds(Integer remainingSeconds) { this.remainingSeconds = remainingSeconds; }
    public boolean isCircumstantial() { return circumstantial; }
}

