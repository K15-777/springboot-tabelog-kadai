package com.example.nagoyameshi.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.nagoyameshi.entity.Reservation;
import com.example.nagoyameshi.entity.User;

public interface ReservationRepository extends JpaRepository<Reservation, Integer> {
	// ログイン中の会員の予約一覧（新しい予約日時順）
	public Page<Reservation> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

	// 店舗削除前に、関連する予約データを一括削除する用
	public void deleteByShopId(Integer shopId);
}
