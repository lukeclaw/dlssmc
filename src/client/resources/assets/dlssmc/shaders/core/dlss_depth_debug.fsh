#version 330

// Minimal debug shader: sample the scene depth (bound as InSampler) and show it as
// grayscale. Proves custom-pipeline + custom-shader loading + depth-sampler binding —
// the plumbing the motion-vector pass needs — before the matrices UBO is added.
uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float d = texture(InSampler, texCoord).r;
    fragColor = vec4(vec3(d), 1.0);
}
