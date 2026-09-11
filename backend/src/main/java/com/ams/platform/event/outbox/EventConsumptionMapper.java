package com.ams.platform.event.outbox;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EventConsumptionMapper {

    /**
     * 登记消费；已存在则不做任何事。
     *
     * @return 1 = 首次消费；0 = 已消费过
     */
    @Insert("INSERT INTO event_consumption (event_id, consumer) "
            + "VALUES (#{eventId}, #{consumer}) "
            + "ON CONFLICT (event_id, consumer) DO NOTHING")
    int tryInsert(@Param("eventId") String eventId, @Param("consumer") String consumer);
}
