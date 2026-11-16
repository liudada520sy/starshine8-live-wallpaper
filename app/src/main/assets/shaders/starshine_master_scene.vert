#version 300 es
precision highp float;

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;

out vec2 vUv;

void main() {
    // 标准全屏 Quad 顶点映射，将纹理 V 轴对齐 Android Bitmap 顶部坐标系
    vUv = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
