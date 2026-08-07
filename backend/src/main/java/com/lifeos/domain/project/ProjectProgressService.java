package com.lifeos.domain.project;

import com.lifeos.domain.task.Task;
import com.lifeos.domain.task.TaskStatus;

import java.util.List;

public class ProjectProgressService {

    public double calculateProgress(Project project, List<Task> tasks) {
        if (tasks.isEmpty()) {
            return 0.0;
        }

        long completedTasks = tasks.stream()
                .filter(task -> task.getStatus() == TaskStatus.COMPLETED)
                .count();

        return (double) completedTasks / tasks.size();
    }
}
