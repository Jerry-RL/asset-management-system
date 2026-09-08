-- 合同模板（数据插槽）与合同 Word 文档关联

CREATE TABLE IF NOT EXISTS contract_template (
    id              BIGSERIAL PRIMARY KEY,
    template_code   VARCHAR(64)  NOT NULL UNIQUE,
    name            VARCHAR(200) NOT NULL,
    contract_type   VARCHAR(50)  NOT NULL DEFAULT 'lease',
    description     VARCHAR(500),
    -- HTML 正文，插槽形如 {{tenantName}}、{{rentAmount}}
    content_html    TEXT         NOT NULL,
    -- 声明本模板使用的插槽 key 列表（JSON 数组）
    slots_json      TEXT,
    version         INTEGER      NOT NULL DEFAULT 1,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by      BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_contract_template_type ON contract_template (contract_type);
CREATE INDEX IF NOT EXISTS idx_contract_template_enabled ON contract_template (enabled);

ALTER TABLE contract ADD COLUMN IF NOT EXISTS template_id BIGINT;
ALTER TABLE contract ADD COLUMN IF NOT EXISTS doc_file_id BIGINT;
ALTER TABLE contract ADD COLUMN IF NOT EXISTS doc_html TEXT;

CREATE INDEX IF NOT EXISTS idx_contract_template_id ON contract (template_id);

-- 预置标准房屋租赁合同模板
INSERT INTO contract_template (template_code, name, contract_type, description, content_html, slots_json, version, enabled)
SELECT
    'LEASE_STANDARD_V1',
    '标准房屋租赁合同',
    'lease',
    '含租户/资产/租金等常用插槽，可用于快速生成并导出 Word',
    $html$
<div style="font-family:'SimSun',serif;line-height:1.8;color:#222;max-width:800px;margin:0 auto;padding:24px;">
  <h1 style="text-align:center;font-size:22px;letter-spacing:4px;">房屋租赁合同</h1>
  <p style="text-align:right;">合同编号：<strong>{{contractNo}}</strong></p>
  <p>甲方（出租方）：<strong>{{lessorName}}</strong></p>
  <p>乙方（承租方）：<strong>{{tenantName}}</strong>（联系电话：{{tenantPhone}}）</p>
  <p>根据《中华人民共和国民法典》及相关法律法规，甲乙双方在平等、自愿基础上，就房屋租赁事宜达成如下协议：</p>
  <h3>第一条 租赁标的</h3>
  <p>1.1 资产编号：{{assetNo}}；资产名称：{{assetName}}。</p>
  <p>1.2 坐落地址：{{assetAddress}}。</p>
  <p>1.3 租赁面积：{{leaseArea}} 平方米（资产建筑面积 {{assetArea}} 平方米）。</p>
  <h3>第二条 租期</h3>
  <p>2.1 租赁期限自 <strong>{{startDate}}</strong> 起至 <strong>{{endDate}}</strong> 止。</p>
  <h3>第三条 租金与保证金</h3>
  <p>3.1 租金类型：{{rentTypeLabel}}；缴费周期：{{paymentCycleLabel}}。</p>
  <p>3.2 每期租金人民币 <strong>{{rentAmount}}</strong> 元整。</p>
  <p>3.3 履约保证金人民币 <strong>{{depositAmount}}</strong> 元整，合同生效后收取。</p>
  <h3>第四条 其他约定</h3>
  <p>{{remark}}</p>
  <h3>第五条 签署</h3>
  <p>本合同一式两份，甲乙双方各执一份，自双方签字（盖章）之日起生效。</p>
  <p style="margin-top:48px;">签署日期：{{signDate}}</p>
  <table style="width:100%;margin-top:32px;border:none;">
    <tr>
      <td style="width:50%;vertical-align:top;">甲方（盖章）：____________<br/>代表人：____________</td>
      <td style="width:50%;vertical-align:top;">乙方（签字/盖章）：____________<br/>证件号：{{tenantIdNo}}</td>
    </tr>
  </table>
</div>
$html$,
    '["contractNo","lessorName","tenantName","tenantPhone","tenantIdNo","assetNo","assetName","assetAddress","assetArea","leaseArea","startDate","endDate","rentTypeLabel","paymentCycleLabel","rentAmount","depositAmount","remark","signDate"]',
    1,
    TRUE
WHERE NOT EXISTS (SELECT 1 FROM contract_template WHERE template_code = 'LEASE_STANDARD_V1');
