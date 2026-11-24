use chrono::{
    DateTime, Datelike, Duration, FixedOffset, NaiveDate, NaiveDateTime, NaiveTime, TimeZone, Utc,
};
use chrono_tz::Tz;
use serde::{Deserialize, Serialize};
use thiserror::Error;

use crate::models::{Priority, Task, WorkSettings};

#[derive(Debug, Error)]
pub enum RuleError {
    #[error("invalid time zone {0}")]
    TimeZone(String),
    #[error("invalid date {0}")]
    Date(String),
    #[error("invalid time {0}")]
    Time(String),
    #[error("invalid work settings")]
    Settings,
    #[error("local time does not exist in time zone")]
    InvalidLocalTime,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ReminderPeriod {
    Morning,
    Evening,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AcknowledgedReminder {
    pub work_date: String,
    pub period: ReminderPeriod,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Reminder {
    pub work_date: String,
    pub period: ReminderPeriod,
    pub task_ids: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TaskAttention {
    pub task_id: String,
    pub overdue: bool,
    pub due_soon: bool,
}

#[derive(Debug, Clone)]
pub struct Evaluation {
    pub visible: Vec<Task>,
    pub attention: Vec<TaskAttention>,
    pub reminder: Option<Reminder>,
}

type RankedTask = (u8, DateTime<Utc>, Task, TaskAttention);

pub fn evaluate(
    now: DateTime<FixedOffset>,
    tasks: &[Task],
    settings: &WorkSettings,
    acknowledged: &[AcknowledgedReminder],
) -> Result<Evaluation, RuleError> {
    validate_settings(settings)?;
    let timezone: Tz = settings
        .time_zone
        .parse()
        .map_err(|_| RuleError::TimeZone(settings.time_zone.clone()))?;
    let now_local = now.with_timezone(&timezone);

    let mut ranked: Vec<RankedTask> = tasks
        .iter()
        .filter(|task| !task.completed)
        .map(|task| {
            let (attention, due) = classify(task, now_local, settings, timezone)?;
            let rank = attention_rank(task.priority, &attention);
            Ok((rank, due, task.clone(), attention))
        })
        .collect::<Result<Vec<_>, RuleError>>()?;
    ranked.sort_by(|left, right| {
        left.0
            .cmp(&right.0)
            .then_with(|| left.1.cmp(&right.1))
            .then_with(|| left.2.task_id.cmp(&right.2.task_id))
    });

    let visible = ranked.iter().map(|(_, _, task, _)| task.clone()).collect();
    let attention = ranked
        .iter()
        .map(|(_, _, _, attention)| attention.clone())
        .collect::<Vec<_>>();
    let reminder = active_reminder(now_local, settings, &ranked, acknowledged)?;

    Ok(Evaluation {
        visible,
        attention,
        reminder,
    })
}

fn validate_settings(settings: &WorkSettings) -> Result<(), RuleError> {
    if settings.workdays.is_empty()
        || settings.workdays.iter().any(|day| !(1..=7).contains(day))
        || settings.reminder_lead_minutes < 0
    {
        return Err(RuleError::Settings);
    }
    let start = parse_clock(&settings.work_start)?;
    let end = parse_clock(&settings.work_end)?;
    if start >= end {
        return Err(RuleError::Settings);
    }
    Ok(())
}

fn classify(
    task: &Task,
    now_local: DateTime<Tz>,
    settings: &WorkSettings,
    timezone: Tz,
) -> Result<(TaskAttention, DateTime<Utc>), RuleError> {
    let due = due_instant(task, timezone)?;
    let instant_now = now_local.to_utc();
    let overdue = instant_now > due;
    let due_date = parse_date(&task.due_date)?;
    let previous = previous_workday(due_date, &settings.workdays)?;
    let due_soon = !overdue && now_local.date_naive() >= previous;
    Ok((
        TaskAttention {
            task_id: task.task_id.clone(),
            overdue,
            due_soon,
        },
        due,
    ))
}

fn attention_rank(priority: Priority, attention: &TaskAttention) -> u8 {
    if attention.overdue {
        0
    } else if matches!(priority, Priority::Urgent | Priority::VeryUrgent) {
        1
    } else if attention.due_soon {
        2
    } else {
        3
    }
}

fn due_instant(task: &Task, timezone: Tz) -> Result<DateTime<Utc>, RuleError> {
    let date = parse_date(&task.due_date)?;
    let time = if let Some(value) = &task.due_time {
        parse_clock(value)?
    } else {
        NaiveTime::from_hms_nano_opt(23, 59, 59, 999_999_999).expect("valid end of day")
    };
    let naive = NaiveDateTime::new(date, time);
    Ok(resolve_local(timezone, naive)?.to_utc())
}

fn resolve_local(timezone: Tz, naive: NaiveDateTime) -> Result<DateTime<Tz>, RuleError> {
    timezone
        .from_local_datetime(&naive)
        .earliest()
        .ok_or(RuleError::InvalidLocalTime)
}

fn active_reminder(
    now_local: DateTime<Tz>,
    settings: &WorkSettings,
    ranked: &[RankedTask],
    acknowledged: &[AcknowledgedReminder],
) -> Result<Option<Reminder>, RuleError> {
    if ranked.is_empty()
        || !settings
            .workdays
            .contains(&now_local.weekday().number_from_monday())
    {
        return Ok(None);
    }
    let start = parse_clock(&settings.work_start)?;
    let end = parse_clock(&settings.work_end)?;
    let evening_start = end - Duration::minutes(i64::from(settings.reminder_lead_minutes));
    let now_time = now_local.time();
    let period = if now_time >= evening_start {
        ReminderPeriod::Evening
    } else if now_time >= start {
        ReminderPeriod::Morning
    } else {
        return Ok(None);
    };
    let task_ids = ranked
        .iter()
        .filter(|(rank, _, _, _)| period == ReminderPeriod::Evening || *rank < 3)
        .map(|(_, _, task, _)| task.task_id.clone())
        .collect::<Vec<_>>();
    if task_ids.is_empty() {
        return Ok(None);
    }
    let work_date = now_local.date_naive().format("%Y-%m-%d").to_string();
    if acknowledged
        .iter()
        .any(|ack| ack.work_date == work_date && ack.period == period)
    {
        return Ok(None);
    }
    Ok(Some(Reminder {
        work_date,
        period,
        task_ids: task_ids.to_vec(),
    }))
}

fn previous_workday(date: NaiveDate, workdays: &[u32]) -> Result<NaiveDate, RuleError> {
    let mut candidate = date - Duration::days(1);
    for _ in 0..14 {
        if workdays.contains(&candidate.weekday().number_from_monday()) {
            return Ok(candidate);
        }
        candidate -= Duration::days(1);
    }
    Err(RuleError::Settings)
}

fn parse_date(value: &str) -> Result<NaiveDate, RuleError> {
    NaiveDate::parse_from_str(value, "%Y-%m-%d").map_err(|_| RuleError::Date(value.to_string()))
}

fn parse_clock(value: &str) -> Result<NaiveTime, RuleError> {
    NaiveTime::parse_from_str(value, "%H:%M").map_err(|_| RuleError::Time(value.to_string()))
}

pub fn acknowledged_set(rows: &[(String, String)]) -> Vec<AcknowledgedReminder> {
    rows.iter()
        .filter_map(|(date, period)| {
            let period = match period.as_str() {
                "morning" => ReminderPeriod::Morning,
                "evening" => ReminderPeriod::Evening,
                _ => return None,
            };
            Some(AcknowledgedReminder {
                work_date: date.clone(),
                period,
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::Deserialize;

    #[derive(Deserialize)]
    struct CasesFile {
        cases: Vec<RuleCase>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct RuleCase {
        name: String,
        now: String,
        settings: WorkSettings,
        #[serde(default)]
        acknowledged: Vec<AcknowledgedReminder>,
        tasks: Vec<Task>,
        expected: Expected,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Expected {
        visible_task_ids: Vec<String>,
        attention: Vec<TaskAttention>,
        reminder: Option<Reminder>,
    }

    #[test]
    fn shared_rule_cases_match() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../shared/protocol/rules/cases.json");
        let content = std::fs::read_to_string(&path)
            .unwrap_or_else(|error| panic!("read {}: {error}", path.display()));
        let cases: CasesFile = serde_json::from_str(&content).expect("parse shared rule cases");
        for case in cases.cases {
            let now = DateTime::parse_from_rfc3339(&case.now).expect("fixture timestamp");
            let evaluation = evaluate(now, &case.tasks, &case.settings, &case.acknowledged)
                .unwrap_or_else(|error| panic!("{}: {error}", case.name));
            let visible_ids = evaluation
                .visible
                .iter()
                .map(|task| task.task_id.clone())
                .collect::<Vec<_>>();
            assert_eq!(visible_ids, case.expected.visible_task_ids, "{}", case.name);
            assert_eq!(
                evaluation.attention, case.expected.attention,
                "{}",
                case.name
            );
            assert_eq!(evaluation.reminder, case.expected.reminder, "{}", case.name);
        }
    }
}
