package com.link.linkagent.creator.media.preflight.model;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 修改清单只暴露创作者真正需要的四种状态操作。 */
public record UpdateEditTaskRequest(
        @NotBlank(message = "修改任务状态不能为空")
        @Pattern(regexp = "TODO|IN_PROGRESS|COMPLETED|IGNORED", message = "修改任务状态不正确")
        String status,

        @Size(max = 1000, message = "修改任务备注长度不能超过1000个字符")
        String note
) {
    @AssertTrue(message = "暂不处理时请填写原因")
    public boolean isNoteValid() {
        return !"IGNORED".equals(status) || (note != null && !note.isBlank());
    }
}
