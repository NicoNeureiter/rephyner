package rephyner.util;

public class Proposal<T> {
    public T value;
    public double logP;
    public double logPBack;

    public Proposal(T value) {
        this.value = value;
        this.logP = 0.0;
        this.logPBack = 0.0;
    }

    public Proposal(T value, double logP, double logPBack) {
        this.value = value;
        this.logP = logP;
        this.logPBack = logPBack;
    }

}
