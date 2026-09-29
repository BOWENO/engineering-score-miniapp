"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.setTarget = setTarget;
exports.takeTarget = takeTarget;
let target = null;
function setTarget(kind, id = '', date) { target = { kind, id, date, token: getApp().globalData.accessToken }; }
function takeTarget(kind) { if (!target || target.kind !== kind)
    return null; const value = target; target = null; return value.token === getApp().globalData.accessToken ? value : null; }
