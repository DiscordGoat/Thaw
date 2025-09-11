package goat.thaw.system.stats;

public class StatInstance {
    private final StatDefinition definition;
    private double value;

    public StatInstance(StatDefinition definition, double value) {
        this.definition = definition;
        this.value = clamp(value);
    }

    public StatDefinition getDefinition() { return definition; }

    public double get() { return value; }

    public void set(double v) { value = clamp(v); }

    public void add(double delta) { set(value + delta); }

    public void subtract(double delta) { set(value - delta); }

    private double clamp(double v) {
        if (v < definition.getMin()) return definition.getMin();
        if (v > definition.getMax()) return definition.getMax();
        return v;
    }
}

