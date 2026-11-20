#!/usr/bin/env bash
# 把所有本地功能分支变基到 upstream/main，再把 main 重建为这些分支的章鱼合并
# （单提交、父为 upstream/main 与各分支，保留分支自己的历史）。
#
# 用法：git sync [--push]
#   --push  成功后用 --force-with-lease 把 main 与各分支推到 origin
# 环境变量：
#   SYNC_REMOTE  上游远端，默认 upstream；同步范围永远是 main 以外的全部本地分支
#
# 分支之间固定的冲突（例如都在某文件末尾追加字符串）只需手工解决一次，
# rerere 会记录解法，以后同样冲突自动复用。
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

remote=${SYNC_REMOTE:-upstream}
base_ref="refs/remotes/$remote/main"
push=0
case ${1:-} in
  --push) push=1 ;;
  "") ;;
  *) echo "用法：git sync [--push]" >&2; exit 2 ;;
esac

tmp=__sync

cleanup() {
  # 冲突现场留给用户解决，不要切分支或删临时分支。
  [[ -n $(git ls-files -u) ]] && return
  if git show-ref --verify --quiet "refs/heads/${tmp}" && [[ $(git rev-parse --abbrev-ref HEAD) == "${tmp}" ]]; then
    git checkout -q main
  fi
  git branch -D "${tmp}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# 变基和 reset --hard 会丢掉未提交的改动。
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "工作树不干净，先提交或 stash 再同步。" >&2
  exit 1
fi

echo "==> 抓取 $remote"
git fetch --prune "$remote"
base=$(git rev-parse "$base_ref")

branches=()
while IFS= read -r b; do
  [[ ${b} == main || ${b} == "${tmp}" ]] || branches+=("${b}")
done < <(git for-each-ref --format='%(refname:short)' refs/heads)

echo "==> 变基到 $(git rev-parse --short "$base")"
for b in "${branches[@]}"; do
  if [[ $(git rev-list --count "$base".."${b}") -eq 0 ]]; then
    echo "    ${b}：已包含，跳过"
    continue
  fi
  echo "    ${b}"
  git checkout -q "${b}"
  if ! git rebase "$base"; then
    cat >&2 <<EOF

${b} 变基冲突，已停下。
解决后用 git add <文件> && git rebase --continue；同样的冲突以后由 rerere 自动复用。
再跑一次 git sync 可继续。
EOF
    exit 1
  fi
done

mergeable=()
for b in "${branches[@]}"; do
  [[ $(git rev-list --count "$base".."${b}") -gt 0 ]] && mergeable+=("${b}")
done

echo "==> 重建 main"
git checkout -q main

if ((${#mergeable[@]})); then
  # 章鱼策略只在无冲突时可用；先在临时分支上逐个合并，冲突解法由 rerere 记录。
  git checkout -q -B "${tmp}" "$base"
  for b in "${mergeable[@]}"; do
    if ! git merge --no-ff -m "集成：合并 ${b}" "${b}" >/dev/null; then
      # rerere 把冲突全部自动解决并加入 index 时，直接提交这次合并。
      if git rev-parse -q --verify MERGE_HEAD >/dev/null && [[ -z $(git ls-files -u) ]]; then
        printf '%s\n' "    ${b}：冲突已由 rerere 自动解决"
        git commit --no-edit -q
        continue
      fi
      cat >&2 <<EOF

${b} 合并冲突，已停在 ${tmp}。
解决并 git commit 后重跑 git sync；同样的冲突以后由 rerere 自动复用。
EOF
      exit 1
    fi
  done
  tree=$(git rev-parse 'HEAD^{tree}')
  parents=(-p "$base")
  for b in "${mergeable[@]}"; do parents+=(-p "${b}"); done
  new=$(git commit-tree "$tree" "${parents[@]}" -m "集成：章鱼合并定制功能分支")
  git checkout -q main
  if [[ $(git rev-parse 'main^{tree}') == "$tree" && $(git log -1 --format=%P main) == $(git log -1 --format=%P "$new") ]]; then
    echo "    main 无变化，保留 $(git rev-parse --short main)"
  else
    git reset --hard "$new" >/dev/null
  fi
else
  # 所有定制分支都已被上游吸收，main 回到上游即可。
  git reset --hard "$base" >/dev/null
fi

echo "==> 结果"
git log -1 --format='    main %h %s'
for b in "${branches[@]}"; do
  printf '    %-16s %s\n' "${b}" "$(git log -1 --format='%h %s' "${b}")"
done

if ((push)); then
  echo "==> 推送到 origin"
  git push --force-with-lease origin main "${branches[@]}"
fi
