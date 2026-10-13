-- 演示主数据：只在 H2（默认）profile 下加载，MySQL 初始化不包含这部分。编码前缀 DM-，按编码幂等插入，不指定 id
INSERT INTO mdm_material (code, name, spec, unit, category, status, deleted) SELECT 'DM-M001', '六角螺栓', 'M8x30', '个', '标准件', 1, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM mdm_material WHERE code = 'DM-M001');
INSERT INTO mdm_material (code, name, spec, unit, category, status, deleted) SELECT 'DM-M002', '平垫圈', 'M8', '个', '标准件', 1, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM mdm_material WHERE code = 'DM-M002');
INSERT INTO mdm_material (code, name, spec, unit, category, status, deleted) SELECT 'DM-M003', '冷轧钢板', '1.0mm', '张', '原材料', 1, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM mdm_material WHERE code = 'DM-M003');
INSERT INTO mdm_material (code, name, spec, unit, category, status, deleted) SELECT 'DM-M004', '轴承', '6204', '套', '外购件', 1, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM mdm_material WHERE code = 'DM-M004');
INSERT INTO mdm_material (code, name, spec, unit, category, status, deleted) SELECT 'DM-M005', '包装纸箱', '40x30x20', '个', '包材', 1, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM mdm_material WHERE code = 'DM-M005');
INSERT INTO mdm_customer (code, name, contact, phone, status, deleted) SELECT 'DM-C001', '华东机械有限公司', '王经理', '13800000001', 1, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM mdm_customer WHERE code = 'DM-C001');
INSERT INTO mdm_customer (code, name, contact, phone, status, deleted) SELECT 'DM-C002', '南方装备制造厂', '李工', '13800000002', 1, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM mdm_customer WHERE code = 'DM-C002');
INSERT INTO mdm_customer (code, name, contact, phone, status, deleted) SELECT 'DM-C003', '北辰贸易公司', '赵总', '13800000003', 1, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM mdm_customer WHERE code = 'DM-C003');
INSERT INTO mdm_supplier (code, name, contact, phone, status, deleted) SELECT 'DM-S001', '金属材料供应商', '陈经理', '13900000001', 1, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM mdm_supplier WHERE code = 'DM-S001');
INSERT INTO mdm_supplier (code, name, contact, phone, status, deleted) SELECT 'DM-S002', '标准件批发商', '周经理', '13900000002', 1, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM mdm_supplier WHERE code = 'DM-S002');
INSERT INTO mdm_supplier (code, name, contact, phone, status, deleted) SELECT 'DM-S003', '包装材料厂', '吴经理', '13900000003', 1, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM mdm_supplier WHERE code = 'DM-S003');
INSERT INTO mdm_warehouse (code, name, address, status, deleted) SELECT 'DM-W01', '原料仓', '一号厂房', 1, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM mdm_warehouse WHERE code = 'DM-W01');
INSERT INTO mdm_warehouse (code, name, address, status, deleted) SELECT 'DM-W02', '成品仓', '二号厂房', 1, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM mdm_warehouse WHERE code = 'DM-W02');
INSERT INTO mdm_warehouse (code, name, address, status, deleted) SELECT 'DM-W03', '备件仓', '三号厂房', 1, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM mdm_warehouse WHERE code = 'DM-W03');
