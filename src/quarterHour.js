function getQuarterHourKey(date = new Date()) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const hours = String(date.getHours()).padStart(2, '0');
  const minutes = String(Math.floor(date.getMinutes() / 15) * 15).padStart(2, '0');
  return `${year}-${month}-${day}T${hours}:${minutes}`;
}

function getNextQuarterHourBoundary(date = new Date()) {
  const next = new Date(date.getTime());
  next.setMilliseconds(0);
  next.setSeconds(0);

  const minutes = next.getMinutes();
  const remainder = minutes % 15;

  if (remainder === 0 && next.getSeconds() === 0) {
    next.setMinutes(minutes + 15, 0, 0);
    return next;
  }

  next.setMinutes((Math.floor(minutes / 15) + 1) * 15, 0, 0);
  return next;
}

function shouldResetHistoryAt(date = new Date()) {
  return date.getMinutes() % 15 === 0 && date.getSeconds() === 0 && date.getMilliseconds() === 0;
}

module.exports = {
  getQuarterHourKey,
  getNextQuarterHourBoundary,
  shouldResetHistoryAt
};
