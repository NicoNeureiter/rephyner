package rephyner.util;

public class Proposal<T> {
    public T value;
    public double logP;

    public Proposal(T value, double logP) {
        this.value = value;
        this.logP = logP;
    }

}
