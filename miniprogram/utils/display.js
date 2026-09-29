"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.chinaTime = chinaTime;
exports.decorate = decorate;
const labels = { WAITING_STATEMENTS: '待收集说明', WAITING_REVIEW: '待审核', INVESTIGATING: '调查中', CORRECTING: '整改中', PENDING_ARCHIVE: '待归档', ARCHIVED: '已归档', IMPORTANT: '重要', GENERAL: '一般', MAJOR: '重大', CRITICAL: '严重', EQUIPMENT: '设备', PROCESS: '工艺', QUALITY: '质量', SAFETY: '安全', OTHER: '其他', LOW: '低', MEDIUM: '中', HIGH: '高', PENDING: '待处理', SUBMITTED: '已提交', APPROVED: '已通过', OVERDUE: '已逾期', RETURNED: '已退回', REJECTED: '已否决', IN_PROGRESS: '进行中', COMPLETED: '已完成', ACCEPTED: '已验收', CONFIRMED: '已确认', INCIDENT_CREATED: '创建异常档案', METADATA_UPDATED: '更新档案信息', STATEMENT_SUBMITTED: '提交责任人说明', STATEMENT_APPROVED: '说明审核通过', STATEMENT_RETURNED: '说明退回修改', EVIDENCE_ADDED: '添加证据', INVESTIGATION_SUBMITTED: '提交调查结论', INVESTIGATION_CONFIRMED: '确认调查结论', RESPONSIBILITY_CONFIRMED: '确认责任认定', CORRECTIVE_ACTION_CREATED: '建立整改措施', CORRECTIVE_ACTION_COMPLETED: '提交整改完成', CORRECTIVE_ACTION_ACCEPTED: '整改验收通过', CORRECTIVE_ACTION_RETURNED: '整改退回', DOSSIER_ARCHIVED: '档案归档', DOSSIER_REOPENED: '档案重新打开', INCIDENT_RELATION_CONFIRMED: '关联异常档案', PERFORMANCE_CASE_CREATED: '发起绩效处理' };
function chinaTime(value) {
    const date = new Date(value);
    if (isNaN(date.getTime()))
        return value;
    const d = new Date(date.getTime() + 8 * 60 * 60 * 1000), pad = (n) => String(n).padStart(2, '0');
    return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}
// Keep machine values intact for permissions and write requests; add display-only fields.
function decorate(value) {
    if (Array.isArray(value))
        return value.map(item => decorate(item));
    if (!value || typeof value !== 'object')
        return value;
    const result = {};
    for (const [key, item] of Object.entries(value)) {
        result[key] = decorate(item);
        if (typeof item === 'string' && ['status', 'severity', 'categoryCode', 'impactLevel', 'eventType', 'rootCauseCategory'].includes(key))
            result[key + 'Text'] = labels[item] || item;
        if (typeof item === 'string' && key.endsWith('At'))
            result[key + 'Text'] = chinaTime(item);
    }
    return result;
}
