#version 330

// Diagnostic split: LEFT half = raw scene depth as grayscale (tests depth sampling);
// RIGHT half = a pure UV gradient that needs no texture (a witness that the pipeline,
// shader and full-screen draw actually run). This separates "plumbing broken" from
// "depth sampling / reverse-Z" as the cause of the black screen.
uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float d = texture(InSampler, texCoord).r;
    if (texCoord.x < 0.5) {
        fragColor = vec4(vec3(d), 1.0);
    } else {
        fragColor = vec4(texCoord.x, texCoord.y, 0.0, 1.0);
    }
}
