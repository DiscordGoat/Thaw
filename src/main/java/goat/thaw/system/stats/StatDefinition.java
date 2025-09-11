package goat.thaw.system.stats;

public class StatDefinition {
    private final String name;
    private final double min;
    private final double max;
    private final double initial;

    public StatDefinition(String name, double initial, double max, double min) {
        this.name = name;
        this.initial = initial;
        this.max = max;
        this.min = min;
    }

    public String getName() { return name; }
    public double getMin() { return min; }
    public double getMax() { return max; }
    public double getInitial() { return initial; }
}

