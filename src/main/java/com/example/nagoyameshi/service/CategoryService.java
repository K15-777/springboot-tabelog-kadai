package com.example.nagoyameshi.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.nagoyameshi.entity.Category;
import com.example.nagoyameshi.form.CategoryForm;
import com.example.nagoyameshi.repository.CategoryRepository;
import com.example.nagoyameshi.repository.ShopRepository;

@Service
public class CategoryService {
	private final CategoryRepository categoryRepository;
	private final ShopRepository shopRepository;

	public CategoryService(CategoryRepository categoryRepository, ShopRepository shopRepository) {
		this.categoryRepository = categoryRepository;
		this.shopRepository = shopRepository;
	}
	
	@Transactional
	public void create(CategoryForm categoryForm) {
		Category category = new Category();
		category.setName(categoryForm.getName());
		categoryRepository.save(category);
	}
	
	@Transactional
	public void update(CategoryForm categoryForm) {
		Category category = categoryRepository.getReferenceById(categoryForm.getId());		
		category.setName(categoryForm.getName());
		categoryRepository.save(category);
	}
	
	// カテゴリを削除する
	// このカテゴリを使用している店舗が1件でも存在する場合は、外部キー制約違反を避けるため削除せずfalseを返す
	@Transactional
	public boolean delete(Integer id) {
		if (shopRepository.existsByCategoryId(id)) {
			return false;
		}

		categoryRepository.deleteById(id);
		return true;
	}
}

