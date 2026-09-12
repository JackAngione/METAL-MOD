// P6 experiment only. Every variant shares full-resolution coverage/depth and production lighting.
// No runtime source includes this file. The harness supplies the actual Standard shade() body.
#ifndef LOD_SCALE
#define LOD_SCALE 2
#endif
#ifndef FIXTURE_COLOR_GAIN
#define FIXTURE_COLOR_GAIN 1
#endif
struct Fixture {
    float2 size;
    float motion;
    float depthOffset;
};
struct FixtureTargets {
    float4 scene [[color(0)]];
    float4 albedo [[color(1)]];
    float4 normal [[color(2)]];
    float4 light [[color(3)]];
    float depth [[depth(any)]];
};
fragment FixtureTargets fixture_geometry(ResolveVaryings in [[stage_in]], constant Fixture &fixture [[buffer(4)]]) {
    float2 p = in.position.xy;
    float2 uv = p / fixture.size;
    float viewDepth = uv.x < .25 ? 32.0 : (uv.x < .5 ? 80.0 : 200.0);
    // Moving one-pixel foregrounds plus a slanted discontinuity exercise missing coarse samples.
    bool thin = uint(p.x + fixture.motion) % 103 == 0;
    bool foreground = p.x > fixture.size.x * .62 + p.y * .12 + fixture.motion
        && p.x < fixture.size.x * .68 + p.y * .12 + fixture.motion;
    if (thin || foreground) viewDepth = 24.0;
    if (viewDepth >= 128.0) viewDepth += fixture.depthOffset;
    float3 color = ((uint(p.x + fixture.motion) / 16 + uint(p.y) / 16) & 1) == 0
        ? float3(.36, .51, .22) : float3(.55, .43, .27);
    if (foreground) color = float3(.75,.15,.08);
    uint packed = uint(round(viewDepth / 1024.0 * 16777215.0));
    float2 normal = mc_encode_normal(foreground ? normalize(float3(.5,0,1)) : float3(0,0,1));
    return {float4(color * FIXTURE_COLOR_GAIN,1), float4(color,1.0/255.0), float4(normal,.5,float((packed>>16)&255)/255.0),
        float4(0,1,float((packed>>8)&255)/255.0,float(packed&255)/255.0), .1 / viewDepth};
}

ResolveTargets read_inputs(uint2 p, texture2d<float> scene, texture2d<float> albedo,
    texture2d<float> normal, texture2d<float> light) {
    return {scene.read(p),albedo.read(p),normal.read(p),light.read(p)};
}

#define FIXTURE_BINDINGS \
    constant PackOptions &options [[buffer(0)]], \
    constant MCShadowFrame &shadowFrame [[buffer(1)]], \
    constant MCResolveCamera &camera [[buffer(2)]], \
    constant McFog &fog [[buffer(3)]], \
    depth2d_array<float> shadowMap [[texture(0)]], sampler shadowSampler [[sampler(0)]], \
    texture2d<float> scene [[texture(1)]], texture2d<float> albedo [[texture(2)]], \
    texture2d<float> normal [[texture(3)]], texture2d<float> light [[texture(4)]]

fragment float4 fixture_band(ResolveVaryings in [[stage_in]], FIXTURE_BINDINGS) {
    uint2 p = min(uint2(in.position.xy) * LOD_SCALE + LOD_SCALE/2, uint2(scene.get_width()-1,scene.get_height()-1));
    ResolveTargets previous = read_inputs(p,scene,albedo,normal,light);
    float z = mc_unpack_view_depth(previous.normal,previous.light);
    if (LOD_SCALE == 2 ? (z < 64 || z >= 128) : z < 128) return float4(0);
    ResolveVaryings sample = {float4(float2(p)+.5,0,1),float2(0)};
    return shade(sample,previous,options,shadowFrame,camera,fog,shadowMap,shadowSampler).scene;
}

fragment float4 fixture_reconstruct(ResolveVaryings in [[stage_in]], FIXTURE_BINDINGS,
    texture2d<float> middle [[texture(5)]], texture2d<float> far [[texture(6)]]) {
    uint2 p = uint2(in.position.xy);
    ResolveTargets previous = read_inputs(p,scene,albedo,normal,light);
    float z = mc_unpack_view_depth(previous.normal,previous.light);
    if (z < 64) return shade(in,previous,options,shadowFrame,camera,fog,shadowMap,shadowSampler).scene;
    int scale = z < 128 ? 2 : 4;
    float2 location = (in.position.xy - (float(scale)/2+.5)) / float(scale);
    int2 base = int2(floor(location));
    float2 f = fract(location);
    float4 sum = float4(0);
    float weights = 0;
    for (int y=0;y<2;y++) for (int x=0;x<2;x++) {
        int2 q = base + int2(x,y);
        int2 extent = (int2(scene.get_width(),scene.get_height())+scale-1)/scale;
        if (any(q<0) || any(q>=extent)) continue;
        uint2 source = min(uint2(q*scale+scale/2),uint2(scene.get_width()-1,scene.get_height()-1));
        float4 sampleNormal=normal.read(source), sampleLight=light.read(source);
        float sampleDepth=mc_unpack_view_depth(sampleNormal,sampleLight);
        // Depth, normal and material/color rejection prevent foreground and texture bleeding.
        if (abs(sampleDepth-z)>.02 || dot(mc_decode_normal(sampleNormal.rg),mc_decode_normal(previous.normal.rg))<.999
            || any(abs(scene.read(source)-previous.scene)>.002)
            || any(abs(albedo.read(source)-previous.albedo)>.002)) continue;
        float4 color = scale==2 ? middle.read(uint2(q)) : far.read(uint2(q));
        if (color.a<=0) continue;
        float weight=(x==0?1-f.x:f.x)*(y==0?1-f.y:f.y);
        sum+=color*weight; weights+=weight;
    }
    if (weights>.001) return sum/weights;
    // Disoccluded pixels always retain current, full-resolution material and lighting.
    return shade(in,previous,options,shadowFrame,camera,fog,shadowMap,shadowSampler).scene;
}

struct FixtureOverlay { float4 color [[color(0)]]; float depth [[depth(any)]]; };
struct FixtureShadow { float depth [[depth(any)]]; };
fragment FixtureShadow fixture_shadow(ResolveVaryings in [[stage_in]]) {
    return {((uint(in.position.x)/8 + uint(in.position.y)/8)&1) == 0 ? .2 : .8};
}
fragment FixtureOverlay fixture_overlay(ResolveVaryings in [[stage_in]], constant Fixture &fixture [[buffer(4)]]) {
    if (in.position.y < fixture.size.y*.43 || in.position.y > fixture.size.y*.46) discard_fragment();
    return {float4(.1,.3,.9,1),.1/48};
}
