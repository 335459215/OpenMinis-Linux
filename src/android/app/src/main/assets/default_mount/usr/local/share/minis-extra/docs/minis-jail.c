// minis-jail — Minis Ultra 技能沙箱包装器
// 在 PRoot 里没有 namespace / Landlock 可用（内核禁用），
// 能真正落下的是 seccomp-BPF + rlimit + 环境清洗 + UID 降权。
//
// 用法:
//   minis-jail [--profile harden|offline|net] [--as MB] [--nproc N]
//              [--nofile N] [--cpu SEC] [--timeout SEC] [--uid UID] -- cmd args...
//
// profile:
//   harden  （默认）禁 ptrace / bpf / perf / keyctl / mount / io_uring / 模块加载
//   offline 在 harden 基础上禁全部 socket 家族调用
//   net     只禁 harden 那组，保留网络
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <signal.h>
#include <stddef.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <linux/audit.h>

#define MAX_BLOCK 64

static int blocked[MAX_BLOCK];
static int nblocked = 0;

static void block(int nr) { if (nblocked < MAX_BLOCK) blocked[nblocked++] = nr; }

static void add_harden(void) {
    block(__NR_ptrace);
    block(__NR_process_vm_readv);
    block(__NR_process_vm_writev);
#ifdef __NR_bpf
    block(__NR_bpf);
#endif
    block(__NR_perf_event_open);
    block(__NR_keyctl);
    block(__NR_add_key);
    block(__NR_request_key);
    block(__NR_mount);
    block(__NR_umount2);
    block(__NR_pivot_root);
    block(__NR_setns);
    block(__NR_unshare);
#ifdef __NR_io_uring_setup
    block(__NR_io_uring_setup);
    block(__NR_io_uring_enter);
    block(__NR_io_uring_register);
#endif
    block(__NR_kexec_load);
    block(__NR_init_module);
    block(__NR_finit_module);
    block(__NR_delete_module);
    block(__NR_reboot);
    block(__NR_swapon);
    block(__NR_swapoff);
    block(__NR_syslog);
    block(__NR_acct);
    block(__NR_settimeofday);
    block(__NR_clock_settime);
    block(__NR_adjtimex);
    block(__NR_open_by_handle_at);
}

static void add_net(void) {
    block(__NR_socket);
    block(__NR_socketpair);
    block(__NR_connect);
    block(__NR_bind);
    block(__NR_listen);
    block(__NR_accept);
    block(__NR_accept4);
    block(__NR_sendto);
    block(__NR_recvfrom);
    block(__NR_sendmsg);
    block(__NR_recvmsg);
    block(__NR_shutdown);
    block(__NR_setsockopt);
    block(__NR_getsockopt);
    block(__NR_getsockname);
    block(__NR_getpeername);
}

static void die(const char *msg) {
    fprintf(stderr, "minis-jail: %s: %s\n", msg, strerror(errno));
    exit(126);
}

static void apply_seccomp(void) {
    struct sock_filter filter[MAX_BLOCK * 2 + 8];
    int i = 0;
    filter[i++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                                               offsetof(struct seccomp_data, arch));
    filter[i++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0);
    filter[i++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS);
    filter[i++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                                               offsetof(struct seccomp_data, nr));
    for (int k = 0; k < nblocked; k++) {
        filter[i++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (unsigned)blocked[k], 0, 1);
        filter[i++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K,
                                                   SECCOMP_RET_ERRNO | (EPERM & SECCOMP_RET_DATA));
    }
    filter[i++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);

    struct sock_fprog prog = { .len = (unsigned short)i, .filter = filter };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) die("PR_SET_NO_NEW_PRIVS");
    if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog) != 0) die("PR_SET_SECCOMP");
}

static void set_limit(int res, rlim_t v, const char *name) {
    struct rlimit rl = { v, v };
    if (setrlimit(res, &rl) != 0) fprintf(stderr, "minis-jail: setrlimit(%s): %s\n", name, strerror(errno));
}

int main(int argc, char **argv) {
    const char *profile = "harden";
    long as_mb = 4096, nproc = 256, nofile = 1024, cpu = 600, timeout_s = 0, uid = -1;
    int i = 1;

    for (; i < argc; i++) {
        if (!strcmp(argv[i], "--")) { i++; break; }
        else if (!strcmp(argv[i], "--profile") && i + 1 < argc) profile = argv[++i];
        else if (!strcmp(argv[i], "--as") && i + 1 < argc) as_mb = atol(argv[++i]);
        else if (!strcmp(argv[i], "--nproc") && i + 1 < argc) nproc = atol(argv[++i]);
        else if (!strcmp(argv[i], "--nofile") && i + 1 < argc) nofile = atol(argv[++i]);
        else if (!strcmp(argv[i], "--cpu") && i + 1 < argc) cpu = atol(argv[++i]);
        else if (!strcmp(argv[i], "--timeout") && i + 1 < argc) timeout_s = atol(argv[++i]);
        else if (!strcmp(argv[i], "--uid") && i + 1 < argc) uid = atol(argv[++i]);
        else break;
    }
    if (i >= argc) {
        fprintf(stderr, "usage: minis-jail [--profile harden|offline|net] [--as MB] [--timeout SEC] -- cmd...\n");
        return 2;
    }

    add_harden();
    if (!strcmp(profile, "offline")) add_net();
    else if (strcmp(profile, "harden") && strcmp(profile, "net")) {
        fprintf(stderr, "minis-jail: unknown profile %s\n", profile);
        return 2;
    }

    set_limit(RLIMIT_AS, (rlim_t)as_mb << 20, "AS");
    set_limit(RLIMIT_NPROC, nproc, "NPROC");
    set_limit(RLIMIT_NOFILE, nofile, "NOFILE");
    set_limit(RLIMIT_CPU, cpu, "CPU");
    set_limit(RLIMIT_CORE, 0, "CORE");

    if (timeout_s > 0) alarm((unsigned)timeout_s);

    if (uid >= 0) {
        if (setgid((gid_t)uid) != 0 || setuid((uid_t)uid) != 0)
            fprintf(stderr, "minis-jail: drop uid %ld failed: %s\n", uid, strerror(errno));
    }

    // 环境清洗：只留必要变量，令牌/密钥不进子进程
    clearenv();
    setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
    setenv("HOME", "/root", 1);
    setenv("LANG", "C.UTF-8", 1);
    setenv("LC_ALL", "C.UTF-8", 1);
    setenv("TERM", "dumb", 1);
    setenv("TMPDIR", "/tmp", 1);
    setenv("MINIS_JAIL", profile, 1);

    apply_seccomp();
    execvp(argv[i], &argv[i]);
    die("execvp");
    return 127;
}
