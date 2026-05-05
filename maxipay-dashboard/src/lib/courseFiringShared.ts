import { SETTINGS_COLLECTION } from "@/lib/onlineOrderingShared";

export const COURSE_FIRING_DOC = "courseFiring";
export const COURSE_FIRING_COLLECTION_PATH = SETTINGS_COLLECTION;

export interface CourseDefinition {
  id: string;
  name: string;
  order: number;
  /** Seconds to wait after the PREVIOUS course is marked READY before firing this course. */
  delayAfterReadySeconds: number;
}

export interface CourseFiringSettings {
  enabled: boolean;
  courses: CourseDefinition[];
}

export const DEFAULT_COURSES: CourseDefinition[] = [
  { id: "DRINKS", name: "Drinks", order: 0, delayAfterReadySeconds: 0 },
  { id: "APPETIZERS", name: "Appetizers", order: 1, delayAfterReadySeconds: 180 },
  { id: "MAIN_COURSES", name: "Main Courses", order: 2, delayAfterReadySeconds: 300 },
  { id: "DESSERTS", name: "Desserts", order: 3, delayAfterReadySeconds: 180 },
];

export const DEFAULT_COURSE_FIRING_SETTINGS: CourseFiringSettings = {
  enabled: false,
  courses: DEFAULT_COURSES,
};

export function parseCourseFiringSettings(
  data: Record<string, unknown> | undefined
): CourseFiringSettings {
  if (!data) return { ...DEFAULT_COURSE_FIRING_SETTINGS, courses: [...DEFAULT_COURSES] };
  const enabled = data.enabled === true;
  const rawCourses = data.courses;
  if (!Array.isArray(rawCourses) || rawCourses.length === 0) {
    return { enabled, courses: [...DEFAULT_COURSES] };
  }
  const courses: CourseDefinition[] = rawCourses
    .map((c: unknown) => {
      if (!c || typeof c !== "object") return null;
      const obj = c as Record<string, unknown>;
      const id = String(obj.id ?? "").trim();
      const name = String(obj.name ?? "").trim();
      if (!id || !name) return null;
      const order = typeof obj.order === "number" ? obj.order : 0;
      const delay =
        typeof obj.delayAfterReadySeconds === "number"
          ? Math.max(0, Math.round(obj.delayAfterReadySeconds))
          : 0;
      return { id, name, order, delayAfterReadySeconds: delay };
    })
    .filter((c): c is CourseDefinition => c !== null)
    .sort((a, b) => a.order - b.order);
  return { enabled, courses: courses.length > 0 ? courses : [...DEFAULT_COURSES] };
}
