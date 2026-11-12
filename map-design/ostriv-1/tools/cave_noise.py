import numpy as np, time
def _h(ix, iy, iz, seed):
    h = (ix.astype(np.uint32) * np.uint32(73856093)) ^ (iy.astype(np.uint32) * np.uint32(19349663)) \
        ^ (iz.astype(np.uint32) * np.uint32(83492791)) ^ np.uint32(seed * 2654435761 & 0xFFFFFFFF)
    h ^= h >> np.uint32(15); h *= np.uint32(2246822519); h ^= h >> np.uint32(13)
    h *= np.uint32(3266489917); h ^= h >> np.uint32(16)
    return h
def gnoise(x, y, z, seed):
    x0, y0, z0 = np.floor(x), np.floor(y), np.floor(z)
    fx, fy, fz = x - x0, y - y0, z - z0
    s = lambda t: t * t * t * (t * (t * 6 - 15) + 10)
    u, v, w = s(fx), s(fy), s(fz)
    ix, iy, iz = x0.astype(np.int64), y0.astype(np.int64), z0.astype(np.int64)
    out = 0
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                h = _h(ix + dx, iy + dy, iz + dz, seed)
                gx = (h & 255) / 127.5 - 1; gy = ((h >> 8) & 255) / 127.5 - 1; gz = ((h >> 16) & 255) / 127.5 - 1
                d = gx * (fx - dx) + gy * (fy - dy) + gz * (fz - dz)
                out = out + d * (u if dx else 1 - u) * (v if dy else 1 - v) * (w if dz else 1 - w)
    return out * 1.6
def lerp_mat(n_out, step, n_coarse):
    m = np.zeros((n_out, n_coarse)); t = np.arange(n_out) / step; i = np.minimum(t.astype(int), n_coarse - 2)
    m[np.arange(n_out), i] = 1 - (t - i); m[np.arange(n_out), i + 1] = t - i
    return m
def coarse(cx, cz, seed, f, ys, step=(4, 8, 4)):
    xs = cx * 16 + np.arange(0, 17, step[0]); zs = cz * 16 + np.arange(0, 17, step[2])
    yc = ys[0] + np.arange(0, len(ys) + step[1], step[1])
    g = gnoise(xs[None, None, :] * f[0], yc[:, None, None] * f[1], zs[None, :, None] * f[2], seed)
    g = np.einsum('ya,azx->yzx', lerp_mat(len(ys), step[1], len(yc)), g)
    g = np.einsum('zb,ybx->yzx', lerp_mat(16, step[2], len(zs)), g)
    return np.einsum('xc,yzc->yzx', lerp_mat(16, step[0], len(xs)), g)
ys = np.arange(-64, 320); Y = ys[:, None, None]
def carve(cx, cz):
    cheese = coarse(cx, cz, 7, (1 / 60, 1 / 40, 1 / 60), ys)
    thr = 0.40 + 0.15 * np.clip((Y + 10) / 50.0, 0, 1)
    n1 = coarse(cx, cz, 8, (1 / 45, 1 / 30, 1 / 45), ys); n2 = coarse(cx, cz, 9, (1 / 45, 1 / 30, 1 / 45), ys)
    air = (cheese > thr) | (n1 * n1 + n2 * n2 < 0.012)
    air &= (Y > -60) & (Y < 70)
    return air, cheese
if __name__ == "__main__":
  t = time.time(); fr = []
  for cx in range(40, 50):
      for cz in range(40, 50):
          a, c = carve(cx, cz); fr.append(a.mean())
  dt = (time.time() - t) / 100
  print(f"{dt*1000:.1f} ms/chunk, x9216 = {dt*9216/60:.1f} min; air fraction mean {np.mean(fr):.3f}, cheese range {c.min():.2f}..{c.max():.2f}")
  a1, _ = carve(10, 10); a2, _ = carve(11, 10)
  print("seam diff (last col vs first col of next):", float(np.abs(a1[:, :, 15].astype(int) - a2[:, :, 0].astype(int)).mean()))
