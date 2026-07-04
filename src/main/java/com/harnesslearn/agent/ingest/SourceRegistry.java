package com.harnesslearn.agent.ingest;

import java.util.List;

/** 从 IngestProperties 提供来源清单，null 安全。 */
public class SourceRegistry {
    private final IngestProperties props;
    public SourceRegistry(IngestProperties props) { this.props = props; }

    public List<Source> sources() {
        return props.sources() == null ? List.of() : props.sources();
    }
}
