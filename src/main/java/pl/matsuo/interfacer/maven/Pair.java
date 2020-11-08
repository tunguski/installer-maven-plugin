package pl.matsuo.interfacer.maven;

import lombok.Value;

/**
 * Simple representation of pair of elements.
 */
@Value
public class Pair<E, F> {

    /**
     * Despite the name it's just a first element in pair.
     */
    public E key;

    /**
     * Despite the name it's just a second element in pair.
     */
    public F value;
}
