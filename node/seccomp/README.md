# `chrome-userns.json`

Docker's own default seccomp profile (moby `profiles/seccomp/default.json`) blocks the syscalls
Chrome's own (non-setuid) namespace sandbox needs — `unshare`, `setns`, `clone`/`clone3` with
namespace flags, and the `mount`/`pivot_root`/`chroot` calls the sandboxed zygote makes while
setting up its new mount namespace — **unless the container has `CAP_SYS_ADMIN`**. That's a static
decision Docker makes when it compiles the profile into a BPF filter at container-create time,
based on the capability set you asked for (`--cap-add`/`--cap-drop`); it does **not** re-check
later if a process gains capabilities inside a namespace it creates for itself. A `--cap-drop ALL`
container (what `node/controller.py` runs, by design) therefore keeps those syscalls blocked even
though unprivileged user namespaces don't need `CAP_SYS_ADMIN` on a kernel that allows them —
which is confirmed on this host (`/proc/sys/kernel/unprivileged_userns_clone` = `1`).

`chrome-userns.json` is Docker's default profile with exactly one addition: an unconditional
(`SCMP_ACT_ALLOW`, no capability condition) rule for
`unshare, setns, clone, clone3, mount, umount2, pivot_root, chroot`. Everything else Docker's
default profile blocks stays blocked. This was arrived at empirically, not by inspection alone:

1. With the stock default profile + `--cap-drop ALL`, Chrome's zygote fails with
   `Failed to move to new namespace: ... errno = Operation not permitted` (confirmed with
   `strace`-equivalent testing: isolating variables one at a time — apparmor made no difference,
   `--cap-add SYS_ADMIN` alone didn't fix it, only `--security-opt seccomp=unconfined` did,
   narrowing it to seccomp specifically).
2. Allowing only `unshare`/`setns`/`clone`/`clone3` unconditionally got past that error but the
   zygote then crashed (exit -1) during its mount-namespace setup.
3. Adding `mount`/`pivot_root`/`chroot` (also unconditional) to the same rule fixed it: Chrome
   started fully sandboxed, with multiple renderer processes each confirmed to hold their own PID
   namespace (`/proc/<pid>/ns/pid` differs per renderer, same `/proc/<pid>/ns/user` across all of
   them — exactly the per-process namespace isolation Chrome's sandbox is supposed to set up),
   under the full hardened flag set this image otherwise runs with (`--cap-drop ALL`,
   `--security-opt no-new-privileges`, non-root `--user`, `--read-only` + tmpfs).

This is a real, if narrow, widening of what an unprivileged process in this container can do:
it can create its own nested user/pid/mount/net namespaces and mount/pivot_root within them. That
is inherent to letting Chrome's own sandbox work without a setuid helper — accepted deliberately
and documented here rather than silently shipped. It does **not** grant any actual Linux
capability (CAP_SYS_ADMIN etc. are still absent from the container's capability set; code inside
still can't, say, load kernel modules or re-mount the host's real filesystems), and
`unprivileged_userns_clone`/`apparmor_restrict_unprivileged_userns` (see
`node/controller.py`'s startup check) are the two host-level knobs that can disable this path
entirely if you'd rather not allow it even this narrowly — `controller.py` checks the kernel sysctl
before using this profile at all, and falls back to `--no-sandbox` (plain `--security-opt
seccomp=default` in that case) with a clear log line if the kernel won't allow it.

Re-derive this file from a newer Docker/moby release by diffing their current
`profiles/seccomp/default.json` against this one — the only difference should be the one added
rule above.

## The tradeoff, stated plainly

Letting an unprivileged process create its own user namespace isn't a narrow, Chrome-specific
exception once you look at what a user namespace actually grants inside itself: a process that
creates one gets full `CAP_SYS_ADMIN`/`CAP_NET_ADMIN`/etc. *within that new namespace*, which is
enough to reach kernel attack surface that's normally gated behind `unprivileged_userns_clone`
for a real reason — it's been the root cause of more than one real container-escape CVE,
precisely because "unprivileged userns + some other kernel bug" is a well-known escalation
pattern. That's not limited to Chrome's own child processes: the widened rule in
`chrome-userns.json` isn't scoped to Chrome's binary or its specific zygote call pattern, it's an
unconditional syscall allow for the whole container, so it opens that same surface for
*everything* running in this image, including the JVM harness. `--cap-drop ALL` and
`--security-opt no-new-privileges` do not reduce this at all: userns creation itself needs no
capability on a kernel that allows it, and `no-new-privileges` only blocks gaining privilege via
`exec`/setuid, not via a namespace a process creates for itself.

This is why `node/controller.py` only applies this profile at all when it can confirm
`unprivileged_userns_clone` is allowed on the host **and** treats the regular (no custom
seccomp) path as the default-safe fallback, not an afterthought — accepting this widening is a
real, deliberate tradeoff for Chrome's own sandbox, not a free hardening win.

**gVisor (`runsc`)** is the alternative worth knowing about if this tradeoff isn't acceptable for
your threat model: running the sandbox container under gVisor's `runsc` OCI runtime intercepts
syscalls in userspace before they ever reach the host kernel, so even a process that successfully
creates a user namespace inside the sandbox is still constrained by gVisor's own syscall
emulation, not the real kernel's. That removes the need to trust this widened profile at all,
at the cost of gVisor's own (real, but much better-audited) attack surface and a meaningful
performance hit for a JVM + headed Chrome workload. Not used here today — plain Docker + this
profile was the tradeoff actually made — but switching `node/compose.yml`'s/`controller.py`'s
sandbox `docker run` to `--runtime=runsc` (with `runsc` installed on the host) is the natural next
step for anyone who wants Chrome's own sandbox without this seccomp widening.
