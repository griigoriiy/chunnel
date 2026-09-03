# Native binaries

`libhev-socks5-tunnel.so` is checked into this repository as a prebuilt JNI dependency. The Android application build does not compile any C/C++ code and does not require the Android NDK.

## Provenance

- Upstream: [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
- Release: [2.17.1](https://github.com/heiher/hev-socks5-tunnel/releases/tag/2.17.1)
- Source commit: [`9a06bc6e7989da54e3d32ff701ef7a7ce4995d3a`](https://github.com/heiher/hev-socks5-tunnel/commit/9a06bc6e7989da54e3d32ff701ef7a7ce4995d3a)
- Android NDK: `25.1.8937393`
- Minimum Android API used for the native build: `26`

The source was checked out recursively at the pinned release. Its submodules were:

| Path | Commit |
|---|---|
| `src/core` | `162dd996299fc2d2bff2dd63728f8a2cd71ed31a` |
| `third-part/hev-task-system` | `328f35d903221b51811b3d02b277d665dfbdc75f` |
| `third-part/lwip` | `2a11c14c7a32887af25a034e82ef18b0b12076ac` |
| `third-part/yaml` | `efa36117a8646d26d12b58e05bac472d7854a70d` |

The JNI libraries were built with the upstream `Android.mk` and `Application.mk`. `PKGNAME` and `CLSNAME` bind the native methods to the Java class retained in this project:

```bash
ndk-build \
  -C /path/to/hev-socks5-tunnel \
  NDK_PROJECT_PATH=. \
  APP_BUILD_SCRIPT=Android.mk \
  NDK_APPLICATION_MK=Application.mk \
  APP_PLATFORM=android-26 \
  'APP_CFLAGS=-O3 -DPKGNAME=hev/sockstun -DCLSNAME=TProxyService' \
  -j4
```

The resulting stripped files from `libs/<abi>/libhev-socks5-tunnel.so` were copied without modification to `app/src/main/jniLibs/<abi>/libhev-socks5-tunnel.so`.

| ABI | SHA-256 |
|---|---|
| `armeabi-v7a` | `9962be11c50c5cf3d3f4b6df279a8d185e3bb0fe8d2813778b549e502d485a50` |
| `arm64-v8a` | `6b79f9dbb08647ce0eb166421ce884415aa7fa7815ff788c7e569ca432f3fca7` |
| `x86` | `af83b568635eb2d1c89904d007de30c24979b563a3b86997b4244362e9b33fbc` |
| `x86_64` | `aa39f691d260be7e259fdd9fac13bf05d42772fd178fb62cc5281fc73218c4c3` |

The prebuilt JNI API is bound to the class name `hev.sockstun.TProxyService`. Keep that class name and its R8 keep rule unless the native libraries are rebuilt with a different `PKGNAME`.

This JNI version exposes start, stop, native liveness and traffic statistics. Release 2.17.0 changed start/stop to return success values and added shutdown synchronization; the Java declarations in `TProxyService` must therefore stay in sync with the checked-in binaries.
