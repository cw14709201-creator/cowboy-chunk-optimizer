# Cowboy's Chunk Optimizer

**Fabric 1.21.1 | Client-Only | Modrinth**

> The most aggressive chunk loading optimization available for Fabric.
> Zero frame drops on chunk load. Smarter scheduling. Faster everything.

---

## The problem with vanilla chunk loading

Every time Minecraft generates or loads a chunk near you, it has to:
1. Build a mesh (geometry) for each 16×16×16 section on worker threads
2. Upload that mesh to the GPU on the **main thread**

Step 2 is where vanilla falls apart. It uploads everything in one shot per tick — no budget, no prioritisation, no mercy. A render distance of 12 with fast movement can produce **30–40ms of GPU upload work in a single frame**, dropping you from 120fps to 30fps instantly. You've felt this: the stutter when you move through a new area, the hitch when you press F3+A.

This mod fixes it at every level of the pipeline.

---

## What it does

### ⚡ Adaptive GPU Upload Budget
The #1 cause of chunk-load FPS drops — fixed.

The mod caps main-thread GPU upload time to a configurable budget per frame (default: **5ms**). When you're standing still, the budget expands to load chunks fast. When you're moving at elytra speed, it compresses to protect framerate. After a teleport or world join, it briefly triples to load the world around you as fast as possible, then ramps back down.

**Vanilla:** unlimited GPU upload → 30–40ms stalls → 20fps during generation  
**With CCO:** 5ms budget, 60fps maintained throughout

### 🎯 Nearest-First + Frustum Priority Scheduling
Vanilla's chunk rebuild queue is FIFO. A section 200 blocks away gets built before the one 10 blocks in front of you. This mod replaces it with a **priority queue** that scores each section by:
- Distance to camera (closest = highest priority)
- Whether the section is in the view frustum (+40% priority boost)
- Predicted travel direction (sections ahead of you get pre-boosted)
- Y-level proximity to eye height (underground sections deprioritised)

Result: the terrain you're actually looking at loads in the order you need it.

### 🔭 Predictive Scheduling
The mod tracks your velocity over a rolling 6-tick window and estimates where you'll be in ~0.8 seconds. Sections in a 35° cone around your predicted travel direction are pre-boosted in the queue — so chunks load in front of you, not behind.

### 🧱 Section Visibility Graph
Before queuing a section for rebuild, the mod checks whether it has any exposed faces. A section completely surrounded by solid sections on all 6 sides has **zero visible geometry** — there's no point building a mesh for it. These sections are culled from the queue entirely until a neighbor changes.

This is huge for cave-heavy terrain: the underground layer from Y=-64 to Y=0 in a typical world has thousands of fully-enclosed sections. Vanilla tries to build all of them. This mod skips them until they need it.

### 🔑 Palette Hash Deduplication
Block light updates, chunk sync events, and certain redstone operations mark sections as "dirty" even when no blocks actually changed. This mod hashes the section's block data (sampling 64 evenly-spaced blocks in a 4×4×4 grid) before queuing a rebuild. If the hash matches the previously-built state, the rebuild is skipped entirely.

### 🌊 Teleport / World-Join Burst Mode
Detects world loads, dimension changes, F3+A reloads, and teleports. Activates a 6-second burst window where the upload budget is tripled, then ramps back down smoothly. The world loads fast when you need it, and FPS is protected when you're actually playing.

### 🧵 Dynamic Thread Pool Scaling
The worker thread pool scales between a minimum and maximum based on how many sections are waiting to be built. During load bursts, extra threads are spun up. During idle periods, they're released. The floor and ceiling are automatically computed from your CPU topology (leaving the main and render threads free).

### 💾 Vertex Buffer Object Pool
Vanilla allocates a fresh GPU buffer every time a section is rebuilt. This mod pools and reuses existing buffers, eliminating the GC pressure and GPU driver allocation stall that happens during heavy chunk generation.

### 🌫 Smooth Chunk Fade-In
New sections fade in over 150ms using a smoothstep curve instead of hard-popping into view. Distant sections (>2 chunks away) fade in; nearby ones appear instantly. The effect is subtle but eliminates the jarring "terrain materialisation" look during fast movement. Near-zero performance cost.

### 🚨 Main Thread Stall Guard
If a single GPU upload call takes longer than expected (driver hiccup, shader compilation, very large section), the mod detects it and stops uploading more sections that frame. After frame-death events (>25ms upload phase), it halves the budget for 30 frames to give the system time to recover.

### 📊 Live Stats HUD
Press **H** to toggle a small overlay showing:
- Current/max upload budget with sparkline
- Sections uploaded per tick, avg upload time
- Worker thread count and scaling events
- Sections fading in
- Stall events and penalty frames
- Sections culled by graph / deduplication
- Player speed and predicted position
- Mesh pool utilisation

---

## Settings at a glance

| Setting | Default | Description |
|---|---|---|
| `uploadBudgetMs` | `5` | Base ms/tick for GPU uploads |
| `adaptiveBudget` | `true` | Scale budget by speed and frame pressure |
| `adaptiveBudgetMin` | `2` | Minimum budget at max speed |
| `adaptiveBudgetMax` | `14` | Maximum budget when stationary |
| `burstMode` | `true` | 3× budget after teleport/world load |
| `burstBudgetMultiplier` | `3.0` | Burst budget multiplier |
| `nearestFirstScheduling` | `true` | Closest sections built first |
| `frustumPriorityScheduling` | `true` | In-view sections prioritised |
| `frustumPriorityBoost` | `0.45` | In-frustum priority multiplier (lower=stronger) |
| `predictiveScheduling` | `true` | Pre-boost sections in travel direction |
| `predictiveLookaheadSeconds` | `0.8` | How far ahead to predict |
| `earlyOcclusionCull` | `true` | Skip fully-enclosed sections |
| `paletteHashDedup` | `true` | Skip rebuilds for unchanged sections |
| `neighborPreloading` | `true` | Pre-invalidate chunk border caches |
| `skipAirSections` | `true` | Skip entirely-empty sections |
| `dynamicThreadScaling` | `true` | Scale worker pool by queue depth |
| `workerThreads` | `0` | 0 = auto (cpu-2) |
| `enableMeshPool` | `true` | Reuse GPU vertex buffers |
| `meshPoolMaxSize` | `256` | Max pooled buffers |
| `enableFade` | `true` | Smooth fade-in for new sections |
| `fadeDurationMs` | `150` | Fade duration |
| `fadeCurveMode` | `1` | 0=linear 1=smoothstep 2=ease-in 3=ease-out |
| `fadeNearbyChunkThreshold` | `2` | Chunks at this distance or closer appear instantly |
| `showHud` | `false` | Live stats overlay (toggle with H) |
| `hudShowTimings` | `false` | Per-system timing breakdown in HUD |

---

## Compatibility

| Mod | Status |
|---|---|
| **Sodium** | ⚠️ Partial — CCO disables its pipeline mixins (Sodium replaces them), keeps velocity, burst, HUD |
| **Lithium** | ✅ Full — different systems |
| **Starlight** | ✅ Full — light engine only |
| **FerriteCore** | ✅ Full — memory only |
| **ModMenu** | ✅ Visual config screen |
| **Cloth Config** | ✅ Config screen (optional) |
| **Iris/Oculus** | ⚠️ Fade disabled (would conflict with shader alpha pipeline) |

**Best combo:** Sodium + CCO + Lithium + FerriteCore

---

## Building

```bash
./gradlew build
```
Requires Java 21, Gradle 8.x. Outputs to `build/libs/`.

---

*Made by Cowboy — clean mods, real gains.*
