#!/usr/bin/env node
/**
 * 「项目分区管理」入口归属锁定的真值表验证。
 *
 * 运行：node scripts/test-asset-scope-lock.mjs
 *
 * 为什么值得单独验：这几个布尔量出错时**不报错**，只表现为「某个下拉选不了」
 * 或「悄悄存下一条归属为空的记录」—— 后者尤其难发现：资产照常创建成功，
 * 只是它不在任何分区 Tab 下（要看出来只能去查 `asset.zone_id`）。
 *
 * 本脚本钉住的是同一条判据的两面：分区**已确定**才锁（新增看 URL、编辑看记录），
 * 未确定就必须可选 —— 锁住的空白配上「锁定入口下分区必填」，表单会彻底交不出去。
 *
 * ⚠️ 需要 Node ≥ 22.6（原生类型擦除；≥ 23.6 已默认开启）。CI 的 frontend-docs job 跑的是
 * Node 20，因此本脚本**不进 CI**：定位与 `scripts/test-record-summary.mjs` 一致，
 * 是「新版本 Node 上就能跑」的本地守卫，正式门禁仍在前端构建。
 */
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

const major = Number(process.versions.node.split(".")[0]);
if (major < 22) {
  console.error(
    `Node ${process.version} 不支持原生类型擦除，请用 Node ≥ 22.6 运行本脚本`,
  );
  process.exit(1);
}

const { assetScopeLock } = await import(
  join(ROOT, "frontend/admin-web/src/lib/assetScopeLock.ts")
);

let passed = 0;
const check = (name, fn) => {
  fn();
  passed++;
  console.log(`  ✓ ${name}`);
};

console.log("入口归属锁定（assetScopeLock）");

check("未锁定（台账页直接新增）→ 三字段全部可选", () => {
  assert.deepEqual(assetScopeLock({ lockScope: false, isEdit: false }), {
    company: false,
    project: false,
    zone: false,
  });
});

check("锁定 + 新增 + 全部分区（没有 zoneId）→ 分区必须可选", () => {
  // 这条就是回归点：分区若被锁，字段既灰化又为空 = 使用者选不了分区；
  // 而锁定入口下分区又是必填，于是表单彻底交不出去
  assert.deepEqual(
    assetScopeLock({
      lockScope: true,
      isEdit: false,
      presetZoneId: null,
      lockedCompanyId: 7,
    }),
    { company: true, project: true, zone: false },
  );
});

check("锁定 + 新增 + 选中具体分区 Tab → 三字段全部锁定", () => {
  assert.deepEqual(
    assetScopeLock({
      lockScope: true,
      isEdit: false,
      presetZoneId: 22,
      lockedCompanyId: 7,
    }),
    { company: true, project: true, zone: true },
  );
});

check(
  "锁定 + 编辑 + 记录本身有分区 → 分区锁定（编辑态不带 zoneId，靠记录回填判定）",
  () => {
    assert.deepEqual(
      assetScopeLock({
        lockScope: true,
        isEdit: true,
        presetZoneId: null,
        recordZoneId: 22,
      }),
      { company: true, project: true, zone: true },
    );
  },
);

check(
  "锁定 + 编辑 + 记录本身无分区 → 分区必须可选（锁住的空白既挪不动也补不上）",
  () => {
    assert.deepEqual(
      assetScopeLock({
        lockScope: true,
        isEdit: true,
        presetZoneId: null,
        recordZoneId: null,
      }),
      { company: true, project: true, zone: false },
    );
  },
);

check(
  "锁定 + 新增 + 资产公司反查失败 → 公司不锁（必填项不能「空白 + 灰化」）",
  () => {
    assert.deepEqual(
      assetScopeLock({
        lockScope: true,
        isEdit: false,
        presetZoneId: 22,
        lockedCompanyId: null,
      }),
      { company: false, project: true, zone: true },
    );
  },
);

check(
  "「锁定但为空」不可能出现：任何输入下 company/project/zone 皆为布尔值",
  () => {
    for (const lockScope of [false, true]) {
      for (const isEdit of [false, true]) {
        for (const presetZoneId of [undefined, null, 0, 22]) {
          for (const recordZoneId of [undefined, null, 0, 22]) {
            for (const lockedCompanyId of [undefined, null, 0, 7]) {
              const lock = assetScopeLock({
                lockScope,
                isEdit,
                presetZoneId,
                recordZoneId,
                lockedCompanyId,
              });
              for (const [field, value] of Object.entries(lock)) {
                assert.equal(typeof value, "boolean", `${field} 必须是布尔值`);
              }
            }
          }
        }
      }
    }
  },
);

check(
  "不锁定的入口不受影响：lockScope=false 时永不锁（台账页的分区本就是可选项）",
  () => {
    for (const presetZoneId of [undefined, null, 22]) {
      for (const recordZoneId of [undefined, null, 22]) {
        assert.deepEqual(
          assetScopeLock({
            lockScope: false,
            isEdit: true,
            presetZoneId,
            recordZoneId,
          }),
          { company: false, project: false, zone: false },
        );
      }
    }
  },
);

console.log(`\n${passed} 项通过`);
