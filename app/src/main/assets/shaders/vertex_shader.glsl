attribute vec4 aPosition;
attribute vec4 aTexSamplingCoord;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTextureCoord;

void main() {
    gl_Position = aPosition;
    vTextureCoord = (uTexTransformationMatrix * aTexSamplingCoord).xy;
}