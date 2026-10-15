package gielinor.builder;

/**
 * A vertex in its bind pose and the bones that move it, strongest first,
 * with weights summing to one.
 */
record Bound(double[] position, String[] bones, double[] weights) {
    /** The bone moving the vertex most. */
    String bone() {
        return bones[0];
    }
}
