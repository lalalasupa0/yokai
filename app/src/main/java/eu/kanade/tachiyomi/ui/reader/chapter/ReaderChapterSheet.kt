package eu.kanade.tachiyomi.ui.reader.chapter

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import com.mikepenz.fastadapter.listeners.ClickEventHook
import eu.kanade.tachiyomi.R
import yokai.i18n.MR
import yokai.util.lang.getString
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.databinding.ReaderChaptersSheetBinding
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.isInNightMode
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.view.collapse
import eu.kanade.tachiyomi.util.view.expand
import eu.kanade.tachiyomi.util.view.isCollapsed
import eu.kanade.tachiyomi.util.view.isExpanded
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ReaderChapterSheet @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    var sheetBehavior: BottomSheetBehavior<View>? = null
    lateinit var viewModel: ReaderViewModel
    var adapter: FastAdapter<ReaderChapterItem>? = null
    private val itemAdapter = ItemAdapter<ReaderChapterItem>()
    var selectedChapterId = -1L

    var loadingPos = 0
    lateinit var binding: ReaderChaptersSheetBinding
    var lastScale = 1f

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = ReaderChaptersSheetBinding.bind(this)
    }

    fun setup(activity: ReaderActivity) {
        viewModel = activity.viewModel
        val fullPrimary = activity.getResourceColor(R.attr.colorSurface)

        val primary = ColorUtils.setAlphaComponent(fullPrimary, 200)

        val hasLightNav = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 || activity.isInNightMode()
        val navPrimary = ColorUtils.setAlphaComponent(
            if (hasLightNav) {
                fullPrimary
            } else {
                Color.BLACK
            },
            200,
        )
        sheetBehavior = BottomSheetBehavior.from(this)
        binding.chaptersButton.setOnClickListener {
            if (sheetBehavior.isExpanded()) {
                sheetBehavior?.collapse()
            } else {
                sheetBehavior?.expand()
            }
        }

        post {
            binding.chapterRecycler.alpha = if (sheetBehavior.isExpanded()) 1f else 0f
            binding.chapterRecycler.isClickable = sheetBehavior.isExpanded()
            binding.chapterRecycler.isFocusable = sheetBehavior.isExpanded()
            val canShowNav = viewModel.getCurrentChapter()?.pages?.size ?: 1 > 1
            if (canShowNav) {
                activity.binding.readerNav.root.isVisible = sheetBehavior.isCollapsed()
            }
        }

        sheetBehavior?.addBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onSlide(bottomSheet: View, progress: Float) {
                    binding.root.isVisible = true
                    binding.pill.alpha = (1 - max(0f, progress)) * 0.25f
                    val trueProgress = max(progress, 0f)
                    activity.binding.readerNav.root.alpha = (1 - abs(progress)).coerceIn(0f, 1f)
                    backgroundTintList =
                        ColorStateList.valueOf(lerpColor(primary, fullPrimary, trueProgress))
                    binding.chapterRecycler.alpha = trueProgress
                    if (activity.sheetManageNavColor && progress > 0f) {
                        activity.window.navigationBarColor =
                            lerpColor(ColorUtils.setAlphaComponent(navPrimary, if (hasLightNav) 0 else 179), navPrimary, trueProgress)
                    }
                    if (lastScale != 1f && scaleY != 1f) {
                        val scaleProgress = ((1f - progress) * (1f - lastScale)) + lastScale
                        scaleX = scaleProgress
                        scaleY = scaleProgress
                        for (i in 0 until childCount) {
                            val childView = getChildAt(i)
                            childView.scaleY = scaleProgress
                        }
                    }
                }

                override fun onStateChanged(p0: View, state: Int) {
                    val canShowNav = (viewModel.getCurrentChapter()?.pages?.size ?: 1) > 1
                    if (state == BottomSheetBehavior.STATE_COLLAPSED) {
                        sheetBehavior?.isHideable = false
                        (binding.chapterRecycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(
                            adapter?.getPosition(viewModel.getCurrentChapter()?.chapter?.id ?: 0L)
                                ?: 0,
                            binding.chapterRecycler.height / 2 - 30.dpToPx,
                        )
                        if (canShowNav) {
                            activity.binding.readerNav.root.isVisible = true
                        }
                        activity.binding.readerNav.root.alpha = 1f
                    }
                    if (state == BottomSheetBehavior.STATE_DRAGGING || state == BottomSheetBehavior.STATE_SETTLING) {
                        if (canShowNav) {
                            activity.binding.readerNav.root.isVisible = true
                        }
                    }
                    if (state == BottomSheetBehavior.STATE_EXPANDED) {
                        if (canShowNav) {
                            activity.binding.readerNav.root.isInvisible = true
                        }
                        activity.binding.readerNav.root.alpha = 0f
                        binding.chapterRecycler.alpha = 1f
                        if (activity.sheetManageNavColor) {
                            activity.window.navigationBarColor =
                                navPrimary
                        }
                    }
                    if (state == BottomSheetBehavior.STATE_HIDDEN) {
                        activity.binding.readerNav.root.alpha = 0f
                        if (canShowNav) {
                            activity.binding.readerNav.root.isInvisible = true
                        }
                        binding.root.isInvisible = true
                    } else if (binding.root.isVisible) {
                        binding.root.isVisible = true
                    }
                    binding.chapterRecycler.isClickable =
                        state == BottomSheetBehavior.STATE_EXPANDED
                    binding.chapterRecycler.isFocusable =
                        state == BottomSheetBehavior.STATE_EXPANDED
                    activity.reEnableBackPressedCallBack()

                    if ((
                        state == BottomSheetBehavior.STATE_COLLAPSED ||
                            state == BottomSheetBehavior.STATE_EXPANDED ||
                            state == BottomSheetBehavior.STATE_HIDDEN
                        ) &&
                        scaleY != 1f
                    ) {
                        scaleX = 1f
                        scaleY = 1f
                        pivotY = 0f
                        translationX = 0f
                        for (i in 0 until childCount) {
                            val childView = getChildAt(i)
                            childView.scaleY = 1f
                        }
                        lastScale = 1f
                    }
                }
            },
        )

        adapter = FastAdapter.with(itemAdapter)
        binding.chapterRecycler.adapter = adapter
        adapter?.onClickListener = { _, _, item, position ->
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                false
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            } else {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                if (item.chapter.id != viewModel.getCurrentChapter()?.chapter?.id) {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    activity.binding.readerNav.leftChapter.isInvisible = true
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    activity.binding.readerNav.rightChapter.isInvisible = true
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    activity.isScrollingThroughPagesOrChapters = true
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }

        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    loadingPos = position
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    val itemView = (binding.chapterRecycler.findViewHolderForAdapterPosition(position) as? ReaderChapterItem.ViewHolder)?.binding
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    itemView?.bookmarkImage?.isVisible = false
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    itemView?.progress?.isVisible = true
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    activity.lifecycleScope.launch {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                        activity.loadChapter(item.chapter)
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    }
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                }
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                true
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            }
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        }
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        adapter?.addEventHook(
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            object : ClickEventHook<ReaderChapterItem>() {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                override fun onBind(viewHolder: RecyclerView.ViewHolder): View? {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    return if (viewHolder is ReaderChapterItem.ViewHolder) {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                        viewHolder.binding.bookmarkButton
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    } else {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                        null
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    }
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                }
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }

        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                override fun onClick(
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    v: View,
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    position: Int,
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    fastAdapter: FastAdapter<ReaderChapterItem>,
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    item: ReaderChapterItem,
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                ) {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    if (!activity.isLoading && sheetBehavior.isExpanded()) {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                        viewModel.toggleBookmark(item.chapter)
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                        refreshList()
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    }
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                }
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            },
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        )
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }

        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        backgroundTintList = ColorStateList.valueOf(
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            if (!sheetBehavior.isExpanded()) {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                primary
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            } else {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                fullPrimary
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            },
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        )
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }

        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        binding.chapterRecycler.addOnScrollListener(
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            object : RecyclerView.OnScrollListener() {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    super.onScrollStateChanged(recyclerView, newState)
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    if (newState == RecyclerView.SCROLL_STATE_IDLE ||
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                        newState == RecyclerView.SCROLL_STATE_SETTLING
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    ) {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                        sheetBehavior?.isDraggable = true
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    } else {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                        sheetBehavior?.isDraggable = !recyclerView.canScrollVertically(-1)
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                    }
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                }
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            },
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        )
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }

        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        binding.chapterRecycler.layoutManager = LinearLayoutManager(context)
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        refreshList()
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
    }
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }

        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
    fun resetChapter() {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        val itemView = (binding.chapterRecycler.findViewHolderForAdapterPosition(loadingPos) as? ReaderChapterItem.ViewHolder)?.binding
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        itemView?.bookmarkImage?.isVisible = true
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        itemView?.progress?.isVisible = false
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
    }
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }

        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
    fun refreshList() {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        launchUI {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            val chapters = viewModel.getChapters()
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }

        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            selectedChapterId = chapters.find { it.isCurrent }?.chapter?.id ?: -1L
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            itemAdapter.clear()
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            itemAdapter.add(chapters)
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }

        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            (binding.chapterRecycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                adapter?.getPosition(viewModel.getCurrentChapter()?.chapter?.id ?: 0L) ?: 0,
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                binding.chapterRecycler.height / 2 - 30.dpToPx,
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            )
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        }
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
    }
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }

        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
    fun lerpColor(colorStart: Int, colorEnd: Int, percent: Float): Int {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        val perc = (percent * 100).roundToInt()
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        return Color.argb(
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            lerpColorCalc(Color.alpha(colorStart), Color.alpha(colorEnd), perc),
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            lerpColorCalc(Color.red(colorStart), Color.red(colorEnd), perc),
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            lerpColorCalc(Color.green(colorStart), Color.green(colorEnd), perc),
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            lerpColorCalc(Color.blue(colorStart), Color.blue(colorEnd), perc),
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        )
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
    }
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }

        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
    fun lerpColorCalc(colorStart: Int, colorEnd: Int, percent: Int): Int {
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
        return (
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            min(colorStart, colorEnd) * (100 - percent) + max(
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                colorStart,
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
                colorEnd,
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            ) * percent
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
            ) / 100
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
    }
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
}
        adapter?.onLongClickListener = { _, _, item, _ ->
            if (!sheetBehavior.isExpanded() || activity.isLoading) {
                false
            } else {
                viewModel.toggleRead(item.chapter)
                refreshList()
                true
            }
        }
