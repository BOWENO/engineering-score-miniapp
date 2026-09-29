export interface ScheduleRow {
  id: string; userId: string; employeeNo: string; displayName: string;
  teamId: string; teamName: string; businessDate: string; shiftCode: string;
  lineId: string; lineName: string; stationId: string; stationName: string;
  status: string; acknowledgedAt?: string;
}
export interface ScheduleFilters {
  businessDate: string; teamId: string; lineId: string; stationId: string;
  userId: string; shiftCode: string; status: string;
}
export function emptyScheduleFilters(): ScheduleFilters {
  return {businessDate:'',teamId:'',lineId:'',stationId:'',userId:'',shiftCode:'',status:''};
}
export function filterSchedules<T extends ScheduleRow>(rows: T[], filter: ScheduleFilters): T[] {
  return rows.filter(row =>
    (!filter.businessDate || row.businessDate === filter.businessDate) &&
    (!filter.teamId || row.teamId === filter.teamId) &&
    (!filter.lineId || row.lineId === filter.lineId) &&
    (!filter.stationId || row.stationId === filter.stationId) &&
    (!filter.userId || row.userId === filter.userId) &&
    (!filter.shiftCode || row.shiftCode === filter.shiftCode) &&
    (!filter.status || row.status === filter.status)
  );
}
export function scheduleOptions(rows: ScheduleRow[], key: 'teamId'|'lineId'|'stationId'|'userId') {
  const labels = {teamId:'teamName',lineId:'lineName',stationId:'stationName',userId:'displayName'} as const;
  return Array.from(new Map(rows.map(row => [row[key], {
    id:row[key], name:key === 'userId' ? `${row.displayName} · ${row.employeeNo}` :
      key === 'stationId' ? `${row.lineName} · ${row.stationName}` : row[labels[key]]
  }])).values()).sort((a,b)=>a.name.localeCompare(b.name,'zh-CN'));
}
