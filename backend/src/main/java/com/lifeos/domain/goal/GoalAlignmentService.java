package com.lifeos.domain.goal;

import com.lifeos.domain.project.Project;
import com.lifeos.domain.task.Task;
import java.util.List;
import java.util.UUID;

public class GoalAlignmentService {

    public boolean isProjectAlignedWithGoal(Project project, Goal goal) {
        if (project.getGoalId() == null) {
            return false;
        }
        return project.getGoalId().equals(goal.getId());
    }

    public boolean isTaskAlignedWithGoal(Task task, Goal goal, Project project) {
        if (task.getProjectId() == null || project.getGoalId() == null) {
            return false;
        }
        return task.getProjectId().equals(project.getId()) && 
               project.getGoalId().equals(goal.getId());
    }
}
