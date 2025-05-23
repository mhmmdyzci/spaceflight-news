package com.example.spaceflightnews.presentation.fragment.home

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.spaceflightnews.core.SpaceflightApp
import com.example.spaceflightnews.databinding.FragmentHomeBinding
import com.example.spaceflightnews.domain.model.Article
import com.example.spaceflightnews.network.SpaceflightApiService
import com.example.spaceflightnews.network.repository.NewsRepositoryImpl
import com.example.spaceflightnews.presentation.fragment.home.adapter.ArticleAdapter
import com.example.spaceflightnews.util.BaseFragment
import com.example.spaceflightnews.util.NetworkUtil
import com.example.spaceflightnews.util.PreferenceHelper
import com.example.spaceflightnews.viewModel.NewsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import com.example.spaceflightnews.R
import java.util.Locale

class HomeFragment : BaseFragment<FragmentHomeBinding>(FragmentHomeBinding::inflate) {

    private lateinit var viewModel: NewsViewModel
    private lateinit var articleAdapter: ArticleAdapter
    private var articleList: List<Article> = emptyList()
    private var isSwipeRefreshing = false
    private var lastToastTime = 0L
    private val toastCooldownMillis = 2000L



    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeToRefresh()
        setupSearch()
        setupObservers()

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initViewModel()
        decideInitialLoad()
    }

    private fun initViewModel() {
        val api = SpaceflightApp.retrofit.create(SpaceflightApiService::class.java)
        val dao = SpaceflightApp.articleDao
        viewModel = NewsViewModel(NewsRepositoryImpl(api, dao))
    }

    private fun setupRecyclerView() {
        articleAdapter = ArticleAdapter { article ->
            if (NetworkUtil.isNetworkAvailable(requireContext())) {
                viewModel.fetchArticle(article.id)
            } else {
                val action = HomeFragmentDirections.actionHomeFragmentToDetailFragment(article)
                findNavController().navigate(action)
            }

        }
        binding.articleRecyclerView.adapter = articleAdapter

        binding.articleRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition()

                val isLastItemVisible = lastVisibleItemPosition == totalItemCount - 1

                if (isLastItemVisible && dy > 0) {
                    if (!viewModel.isLoading() && !viewModel.isLastPage() && !viewModel.isCurrentlySearching()) {
                        if (NetworkUtil.isNetworkAvailable(requireContext())) {
                            viewModel.fetchArticles(requireContext())
                        } else {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastToastTime > toastCooldownMillis) {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.no_internet_connection),
                                    Toast.LENGTH_SHORT
                                ).show()
                                lastToastTime = currentTime
                            }
                        }
                    }
                }
            }
        })

    }


    private fun setupObservers() {
        viewModel.articles.observe(viewLifecycleOwner) {
            articleList = it
            articleAdapter.submitList(it)
            if (!getFormattedLastUpdate().isEmpty() && it.isNotEmpty()) {
                binding.lastUpdateLayout.visibility = View.VISIBLE
                binding.lastUpdateText.text = getFormattedLastUpdate()
            } else {
                binding.lastUpdateLayout.visibility = View.GONE
            }
        }

        lifecycleScope.launchWhenStarted {
            viewModel.article.collect { event ->
                event?.getContentIfNotHandled()?.let { article ->
                    val action = HomeFragmentDirections.actionHomeFragmentToDetailFragment(article)
                    findNavController().navigate(action)
                }
            }
        }


        viewModel.loading.observe(viewLifecycleOwner) {
            if (isSwipeRefreshing) {
                binding.swipeRefreshLayout.isRefreshing = false
                isSwipeRefreshing = false
            } else{
                isShowMainLoading(it)
            }
        }

        viewModel.error.observe(viewLifecycleOwner) {
            if (it.isNotBlank()) {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            }
        }


    }

    private fun setupSwipeToRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            if (NetworkUtil.isNetworkAvailable(requireContext()) && !viewModel.isLoading()) {
                isSwipeRefreshing = true
                viewModel.refreshFromApi(requireContext())

            } else {
                isSwipeRefreshing = false
                binding.swipeRefreshLayout.isRefreshing = false
                Toast.makeText(requireContext(), getString(R.string.no_internet_connection), Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun setupSearch() {

        binding.editTextSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                startIndex: Int,
                removedCharCount: Int,
                addedCharCount: Int
            ) {
            }

            override fun onTextChanged(
                text: CharSequence?,
                startIndex: Int,
                beforeCharCount: Int,
                newCharCount: Int
            ) {
            }

            override fun afterTextChanged(updatedText: Editable?) {
                if (updatedText.toString().isEmpty() == true) {
                    binding.lastUpdateLayout.visibility = View.VISIBLE
                    binding.editTextSearch.clearFocus()
                    viewModel.isSearching = false
                    refreshList()
                } else {
                    binding.lastUpdateLayout.visibility = View.GONE
                    viewModel.isSearching = false
                    filterList(updatedText.toString(), articleList)
                }
            }
        })


    }

    private fun decideInitialLoad() {
        if (NetworkUtil.isNetworkAvailable(requireContext())) {
            viewModel.refreshFromApi(requireContext())
        } else {
            viewModel.loadCachedArticles()
        }
    }

    fun getFormattedLastUpdate(): String {
        val lastUpdate = PreferenceHelper.getLastUpdateTime(requireContext())
        return if (lastUpdate != -1L) {
            val sdf = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
            "Last Update: ${sdf.format(Date(lastUpdate))}"
        } else {
            ""
        }
    }

    fun refreshList() {
        articleAdapter.submitList(articleList)
    }

    private fun filterList(searchQuery: String, originalList: List<Article>) {
        val filteredList = originalList.filter { article ->
            article.title.contains(searchQuery, ignoreCase = true) == true
        }
        articleAdapter.submitList(filteredList)
    }
}

