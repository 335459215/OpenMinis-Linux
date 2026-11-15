# Minis extra toolkit (CAS / seccomp jail / rustfs / kotlin memory)
# 由 default_mount 覆盖层安装到 guest，登录 shell 自动加载。

EXTRA_ROOT=/usr/local/share/minis-extra

# 1) 保证可执行位（APK 解包可能丢 mode）
for f in /usr/local/bin/minis-cas /usr/local/bin/minis-jail /usr/local/bin/minis-rustfs; do
    [ -f "$f" ] && chmod +x "$f" 2>/dev/null
done
[ -f /usr/local/lib/miniscas/libcdc.so ] && chmod +x /usr/local/lib/miniscas/libcdc.so 2>/dev/null

# 2) 工具路径
case ":$PATH:" in
    *":/usr/local/bin:"*) ;;
    *) export PATH="/usr/local/bin:$PATH" ;;
esac

# 3) 可选：Kotlin 记忆引擎（guest 内有 java 时可用）
if [ -f "$EXTRA_ROOT/kotlin-memory/mem.jar" ] && command -v java >/dev/null 2>&1; then
    alias minis-memory-kotlin='java -cp /usr/local/share/minis-extra/kotlin-memory/mem.jar:/usr/local/share/minis-extra/kotlin-memory/sqlite-jdbc.jar minis.memory.MainKt'
fi

# 4) 可选：沙箱快捷方式
alias minis-jail-offline='minis-jail --profile offline --'
alias minis-jail-harden='minis-jail --profile harden --'

# 5) 提示只打一次（避免污染每条命令）
if [ -z "$MINIS_EXTRA_NOTICED" ] && [ -t 1 ]; then
    export MINIS_EXTRA_NOTICED=1
    echo "[minis-extra] minis-cas / minis-jail / minis-rustfs 已就绪" >&2
fi
