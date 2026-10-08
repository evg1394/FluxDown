@testable import FluxBridge
import FluxDomain
import FluxRustBindings
import Testing

/// 「文件已存在」询问经 UniFFI DTO ↔ 领域模型的映射：字段、可选值与动作双向保真。
struct SelectionMappingTests {
    @Test func fileExistsRequestKeepsEveryFieldAndAllowedActions() {
        let dto = SelectionRequestDto(
            requestId: "r1",
            taskId: "t1",
            kind: .fileExists(
                fileName: "a.zip",
                saveDir: "/dl",
                existingSize: 1024,
                existingModifiedUnixMs: 1_760_000_000_000,
                incomingSize: nil,
                renamePreview: "a (1).zip",
                actions: [.rename, .overwrite]
            ),
            defaultChoice: .fileExists(action: .rename),
            deadlineUnixMs: 42
        )
        let request = dto.domain
        let conflict = request.fileConflict
        #expect(conflict?.fileName == "a.zip")
        #expect(conflict?.saveDir == "/dl")
        #expect(conflict?.existingSize == 1024)
        #expect(conflict?.existingModifiedUnixMs == 1_760_000_000_000)
        #expect(conflict?.incomingSize == nil)
        #expect(conflict?.renamePreview == "a (1).zip")
        #expect(conflict?.actions == [.rename, .overwrite])
        #expect(conflict?.allows(.skip) == false)
        #expect(request.defaultChoice == .fileExists(action: .rename))
    }

    @Test func fileExistsOutcomesRoundTripEveryAction() {
        for action in [FileExistsAction.rename, .overwrite, .skip] {
            let dto = SelectionOutcome.fileExists(action: action).dto
            #expect(dto.domain == .fileExists(action: action))
        }
        #expect(SelectionOutcome.cancelled.dto.domain == .cancelled)
    }
}
