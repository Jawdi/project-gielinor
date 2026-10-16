package gielinor.builder;

/** Where in a texture a model's faces of a colour find that colour. */
interface ColourMap {
    /** Texture coordinates of a colour's cell. */
    double[] uv(int rgb);
}
